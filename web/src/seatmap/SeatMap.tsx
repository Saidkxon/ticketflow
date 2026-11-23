import { memo, useCallback, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { formatMoney } from '../money';
import { buildGeometry, SEAT_SIZE, type RowShape, type SeatShape } from './geometry';
import type { PublishedLayout } from '../api/events';

type SeatState = 'available' | 'unavailable' | 'selected';

interface SeatProps {
  seat: SeatShape;
  state: SeatState;
  currency: string;
  /** True for the one seat that can be reached with the Tab key (roving tabindex). */
  tabbable: boolean;
  onActivate: (seat: SeatShape) => void;
  onFocusSeat: (seatId: string) => void;
}

function describe(seat: SeatShape, state: SeatState, currency: string): string {
  const base = `${seat.sectionName}, row ${seat.rowLabel}, seat ${seat.number}, ${seat.tier}, ${formatMoney(seat.priceMinor, currency)}`;
  return state === 'unavailable' ? `${base}, not available` : state === 'selected' ? `${base}, selected` : base;
}

/**
 * One seat. It is memoized: when availability changes, only the seats whose own state changed are drawn again, which is
 * what keeps a map with 2,000 seats smooth (NFR-10). The colors are not the only sign of the state: a taken seat is
 * hatched, a selected seat has a thick outline.
 */
const Seat = memo(function Seat({ seat, state, currency, tabbable, onActivate, onFocusSeat }: SeatProps) {
  return (
    <g
      className={`seat seat-${state}`}
      role="checkbox"
      aria-checked={state === 'selected'}
      aria-disabled={state === 'unavailable'}
      aria-label={describe(seat, state, currency)}
      tabIndex={tabbable ? 0 : -1}
      data-seat-id={seat.id}
      transform={`translate(${seat.x} ${seat.y})`}
      onClick={state === 'unavailable' ? undefined : () => onActivate(seat)}
      onFocus={() => onFocusSeat(seat.id)}
    >
      <rect width={SEAT_SIZE} height={SEAT_SIZE} rx={5} />
      <text x={SEAT_SIZE / 2} y={SEAT_SIZE / 2} dy="0.35em" textAnchor="middle" aria-hidden="true">
        {seat.number}
      </text>
    </g>
  );
});

export interface SeatMapProps {
  layout: PublishedLayout;
  unavailable: ReadonlySet<string>;
  selected: ReadonlySet<string>;
  /** False until the first availability answer arrived: then seats cannot be chosen yet. */
  interactive: boolean;
  onToggle: (seat: SeatShape) => void;
}

/** The seat map of an event as one SVG, drawn from the frozen layout (ADR-0010, ADR-0011). */
export function SeatMap({ layout, unavailable, selected, interactive, onToggle }: SeatMapProps) {
  const geometry = useMemo(() => buildGeometry(layout), [layout]);
  const firstSeatId = geometry.rows[0]?.seats[0]?.id ?? null;
  const [focusedId, setFocusedId] = useState<string | null>(null);
  const tabbableId = focusedId !== null && geometry.seatsById.has(focusedId) ? focusedId : firstSeatId;
  const svgRef = useRef<SVGSVGElement>(null);

  const onActivate = useCallback(
    (seat: SeatShape) => {
      setFocusedId(seat.id);
      onToggle(seat);
    },
    [onToggle],
  );
  const onFocusSeat = useCallback((seatId: string) => setFocusedId(seatId), []);

  function focusSeat(seat: SeatShape | undefined) {
    if (!seat) {
      return;
    }
    setFocusedId(seat.id);
    svgRef.current?.querySelector<SVGGElement>(`[data-seat-id="${seat.id}"]`)?.focus();
  }

  function onKeyDown(event: KeyboardEvent<SVGSVGElement>) {
    const id = (event.target as Element).closest('[data-seat-id]')?.getAttribute('data-seat-id');
    const current = id ? geometry.seatsById.get(id) : undefined;
    if (!current) {
      return;
    }
    const rowIndex = geometry.rows.findIndex((row) => row.seats.includes(current));
    const row: RowShape = geometry.rows[rowIndex];
    const column = row.seats.indexOf(current);
    switch (event.key) {
      case 'ArrowRight':
        focusSeat(row.seats[column + 1]);
        break;
      case 'ArrowLeft':
        focusSeat(row.seats[column - 1]);
        break;
      case 'ArrowDown': {
        const below = geometry.rows[rowIndex + 1];
        focusSeat(below?.seats[Math.min(column, below.seats.length - 1)]);
        break;
      }
      case 'ArrowUp': {
        const above = geometry.rows[rowIndex - 1];
        focusSeat(above?.seats[Math.min(column, above.seats.length - 1)]);
        break;
      }
      case 'Home':
        focusSeat(row.seats[0]);
        break;
      case 'End':
        focusSeat(row.seats[row.seats.length - 1]);
        break;
      case ' ':
      case 'Enter':
        if (!unavailable.has(current.id) || selected.has(current.id)) {
          onActivate(current);
        }
        break;
      default:
        return;
    }
    event.preventDefault();
  }

  function stateOf(seatId: string): SeatState {
    if (selected.has(seatId)) {
      return 'selected';
    }
    return unavailable.has(seatId) ? 'unavailable' : 'available';
  }

  return (
    <div className="seatmap-scroll" tabIndex={-1}>
      <svg
        ref={svgRef}
        className={interactive ? 'seatmap' : 'seatmap seatmap-waiting'}
        role="group"
        aria-label="Seat map"
        aria-busy={!interactive}
        width={geometry.width}
        height={geometry.height}
        viewBox={`0 0 ${geometry.width} ${geometry.height}`}
        onKeyDown={onKeyDown}
      >
        <defs>
          <pattern id="seat-taken" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <rect width="6" height="6" className="seat-taken-ground" />
            <line x1="0" y1="0" x2="0" y2="6" className="seat-taken-line" />
          </pattern>
        </defs>
        {geometry.sections.map((section) => (
          <g key={section.id} role="group" aria-label={`${section.name}, ${section.tier}`}>
            <text className="section-title" x={8} y={section.y + 18}>
              {section.name} · {section.tier} · {formatMoney(section.priceMinor, layout.currency)}
            </text>
            {section.rows.map((row) => (
              <g key={row.key}>
                <text className="row-label" x={8} y={row.y + SEAT_SIZE / 2} dy="0.35em" aria-hidden="true">
                  {row.label}
                </text>
                {row.seats.map((seat) => (
                  <Seat
                    key={seat.id}
                    seat={seat}
                    state={stateOf(seat.id)}
                    currency={layout.currency}
                    tabbable={interactive && seat.id === tabbableId}
                    onActivate={interactive ? onActivate : noop}
                    onFocusSeat={onFocusSeat}
                  />
                ))}
              </g>
            ))}
          </g>
        ))}
      </svg>
    </div>
  );
}

function noop(): void {
  // Seats cannot be chosen before the first availability answer.
}

/** The legend under the map: the three seat states, drawn with the same shapes as the seats. */
export function SeatLegend() {
  return (
    <ul className="seat-legend" aria-label="Legend">
      {(['available', 'selected', 'unavailable'] as const).map((state) => (
        <li key={state}>
          <svg width={SEAT_SIZE} height={SEAT_SIZE} aria-hidden="true" className={`seat seat-${state}`}>
            <rect width={SEAT_SIZE} height={SEAT_SIZE} rx={5} />
          </svg>
          {state === 'available' ? 'Available' : state === 'selected' ? 'Your choice' : 'Not available'}
        </li>
      ))}
    </ul>
  );
}
