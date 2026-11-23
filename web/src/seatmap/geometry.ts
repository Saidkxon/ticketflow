import type { PublishedLayout } from '../api/events';

export const SEAT_SIZE = 24;
const SEAT_GAP = 4;
export const PITCH = SEAT_SIZE + SEAT_GAP;
export const ROW_LABEL_WIDTH = 30;
const SECTION_HEADER = 28;
const SECTION_GAP = 18;
const PADDING = 8;

/** One seat with everything the map needs to draw it and to describe it to a screen reader. */
export interface SeatShape {
  id: string;
  number: number;
  rowLabel: string;
  sectionName: string;
  tier: string;
  priceMinor: number;
  x: number;
  y: number;
}

export interface RowShape {
  key: string;
  label: string;
  y: number;
  seats: SeatShape[];
}

export interface SectionShape {
  id: string;
  name: string;
  tier: string;
  priceMinor: number;
  y: number;
  rows: RowShape[];
}

export interface MapGeometry {
  sections: SectionShape[];
  /** Every row of the map from top to bottom, for moving between rows with the arrow keys. */
  rows: RowShape[];
  seatsById: Map<string, SeatShape>;
  width: number;
  height: number;
}

/**
 * Places every seat of the layout on a grid: sections one below the other, rows below each other, seats left to right.
 * The layout is frozen once an event is published, so this is computed once per event.
 */
export function buildGeometry(layout: PublishedLayout): MapGeometry {
  const priceOfTier = new Map(layout.priceTiers.map((tier) => [tier.id, tier.priceMinor]));
  const sections: SectionShape[] = [];
  const rows: RowShape[] = [];
  const seatsById = new Map<string, SeatShape>();
  let y = PADDING;
  let widest = 0;

  for (const section of layout.sections) {
    const priceMinor = priceOfTier.get(section.tierId) ?? 0;
    const sectionY = y;
    y += SECTION_HEADER;
    const sectionRows: RowShape[] = [];
    for (const row of section.rows) {
      const seats: SeatShape[] = row.seats.map((seat, index) => {
        const shape: SeatShape = {
          id: seat.id,
          number: seat.number,
          rowLabel: row.label,
          sectionName: section.name,
          tier: section.tier,
          priceMinor,
          x: PADDING + ROW_LABEL_WIDTH + index * PITCH,
          y,
        };
        seatsById.set(shape.id, shape);
        return shape;
      });
      const shape: RowShape = { key: `${section.id}/${row.label}`, label: row.label, y, seats };
      sectionRows.push(shape);
      rows.push(shape);
      widest = Math.max(widest, PADDING + ROW_LABEL_WIDTH + row.seats.length * PITCH);
      y += PITCH;
    }
    sections.push({
      id: section.id,
      name: section.name,
      tier: section.tier,
      priceMinor,
      y: sectionY,
      rows: sectionRows,
    });
    y += SECTION_GAP;
  }
  return { sections, rows, seatsById, width: Math.max(widest + PADDING, 200), height: y + PADDING };
}
