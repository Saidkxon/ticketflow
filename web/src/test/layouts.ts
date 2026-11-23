import type { PublishedLayout } from '../api/events';

interface SectionSpec {
  name: string;
  tier?: string;
  rows: number;
  seatsPerRow: number;
}

const TIERS = [
  { id: 'tier-std', name: 'Standard', priceMinor: 20000000 },
  { id: 'tier-vip', name: 'VIP', priceMinor: 50000000 },
];

/** A layout for tests. Seat IDs are readable: "s0-A-3" is section 0, row A, seat 3. */
export function makeLayout(specs: SectionSpec[]): PublishedLayout {
  return {
    eventId: 'event-1',
    layoutVersion: 3,
    currency: 'UZS',
    priceTiers: TIERS,
    totalSeats: specs.reduce((sum, spec) => sum + spec.rows * spec.seatsPerRow, 0),
    sections: specs.map((spec, index) => {
      const tier = TIERS.find((candidate) => candidate.name === (spec.tier ?? 'Standard')) ?? TIERS[0];
      return {
        id: `section-${index}`,
        name: spec.name,
        tierId: tier.id,
        tier: tier.name,
        rows: Array.from({ length: spec.rows }, (_, rowIndex) => {
          const label = String.fromCharCode(65 + rowIndex);
          return {
            label,
            seats: Array.from({ length: spec.seatsPerRow }, (_, seatIndex) => ({
              id: `s${index}-${label}-${seatIndex + 1}`,
              number: seatIndex + 1,
            })),
          };
        }),
      };
    }),
  };
}
