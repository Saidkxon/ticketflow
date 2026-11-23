import { formatMoney } from '../money';
import type { SeatShape } from './geometry';
import { MAX_SEATS_PER_HOLD } from './limits';

interface Props {
  seats: SeatShape[];
  currency: string;
  onRemove: (seat: SeatShape) => void;
}

/** The seats the person chose and what they cost together. The server calculates the real total when it holds them. */
export function SelectionSummary({ seats, currency, onRemove }: Props) {
  const total = seats.reduce((sum, seat) => sum + seat.priceMinor, 0);
  return (
    <section aria-labelledby="selection-heading" className="selection">
      <h2 id="selection-heading">Your seats</h2>
      {seats.length === 0 ? (
        <p>Choose up to {MAX_SEATS_PER_HOLD} seats on the map.</p>
      ) : (
        <>
          <ul>
            {seats.map((seat) => (
              <li key={seat.id}>
                <span>
                  {seat.sectionName}, row {seat.rowLabel}, seat {seat.number} — {formatMoney(seat.priceMinor, currency)}
                </span>{' '}
                <button
                  type="button"
                  onClick={() => onRemove(seat)}
                  aria-label={`Remove ${seat.sectionName}, row ${seat.rowLabel}, seat ${seat.number}`}
                >
                  Remove
                </button>
              </li>
            ))}
          </ul>
          <p>
            <strong>
              Total: <span data-testid="total">{formatMoney(total, currency)}</span>
            </strong>
          </p>
        </>
      )}
    </section>
  );
}
