import { describe, expect, it } from 'vitest';
import {
  formFromLayout,
  layoutErrorsFromServer,
  majorToMinor,
  minorToMajor,
  seatTotal,
  starterLayout,
  toLayoutRequest,
  validateLayout,
  type LayoutForm,
} from './layoutForm';

function valid(overrides: Partial<LayoutForm> = {}): LayoutForm {
  return {
    currency: 'UZS',
    tiers: [
      { name: 'Standard', price: '150000' },
      { name: 'VIP', price: '400000.50' },
    ],
    sections: [
      { name: 'Stalls', tier: 'Standard', rows: '3', seatsPerRow: '4' },
      { name: 'Front', tier: 'vip', rows: '2', seatsPerRow: '5' },
    ],
    ...overrides,
  };
}

describe('prices', () => {
  it('turns whole units into minor units and back', () => {
    expect(majorToMinor('150000')).toBe(15000000);
    expect(majorToMinor('150.5')).toBe(15050);
    expect(majorToMinor(' 0.01 ')).toBe(1);
    expect(minorToMajor(15000000)).toBe('150000');
    expect(minorToMajor(15050)).toBe('150.50');
    expect(minorToMajor(5)).toBe('0.05');
  });

  it('refuses what is not a price above zero within the limit', () => {
    for (const bad of ['', '0', '0.00', '-5', '1,5', '1.234', 'abc', '1e3', '10000000001', '99999999999', '1234567890123']) {
      expect(majorToMinor(bad), bad).toBeNull();
    }
    expect(majorToMinor('10000000000')).toBe(1_000_000_000_000);
  });
});

describe('validateLayout', () => {
  it('accepts a good layout, matching tier names without regard to case', () => {
    expect(validateLayout(valid())).toBeNull();
  });

  it('accepts the starter once it has a price', () => {
    const starter = starterLayout();
    starter.tiers[0].price = '100000';

    expect(validateLayout(starter)).toBeNull();
    expect(seatTotal(starter)).toBe(200);
  });

  it('wants every tier named, differently, and priced', () => {
    const errors = validateLayout(
      valid({
        tiers: [
          { name: 'Gold', price: '' },
          { name: ' gold ', price: '10' },
          { name: '', price: '10' },
        ],
        sections: [{ name: 'A', tier: 'Gold', rows: '1', seatsPerRow: '1' }],
      }),
    );

    expect(errors?.tiers[0]?.price).toBeDefined();
    expect(errors?.tiers[1]?.name).toMatch(/different/);
    expect(errors?.tiers[2]?.name).toMatch(/required/);
  });

  it('wants every section named, differently, on a known tier, with a sensible size', () => {
    const errors = validateLayout(
      valid({
        sections: [
          { name: 'Hall', tier: 'Standard', rows: '27', seatsPerRow: '4' },
          { name: 'hall', tier: 'Nothing', rows: '0', seatsPerRow: '101' },
          { name: 'Third', tier: 'Standard', rows: 'x', seatsPerRow: '' },
        ],
      }),
    );

    expect(errors?.sections[0]?.rows).toBeDefined();
    expect(errors?.sections[1]?.name).toMatch(/different/);
    expect(errors?.sections[1]?.tier).toBeDefined();
    expect(errors?.sections[1]?.rows).toBeDefined();
    expect(errors?.sections[1]?.seatsPerRow).toBeDefined();
    expect(errors?.sections[2]?.rows).toBeDefined();
    expect(errors?.sections[2]?.seatsPerRow).toBeDefined();
  });

  it('limits the number of tiers, sections and seats', () => {
    const tiers = Array.from({ length: 11 }, (_, i) => ({ name: `T${i}`, price: '1' }));
    const sections = Array.from({ length: 31 }, (_, i) => ({ name: `S${i}`, tier: 'T0', rows: '1', seatsPerRow: '1' }));
    const tooMany = validateLayout(valid({ tiers, sections }));
    const tooBig = validateLayout(
      valid({
        sections: [
          { name: 'A', tier: 'Standard', rows: '26', seatsPerRow: '100' },
          { name: 'B', tier: 'Standard', rows: '26', seatsPerRow: '100' },
        ],
      }),
    );

    expect(tooMany?.general.join(' ')).toMatch(/1 to 10 price tiers/);
    expect(tooMany?.general.join(' ')).toMatch(/1 to 30 sections/);
    expect(tooBig?.general.join(' ')).toMatch(/5000 seats/);
  });

  it('asks for a currency from the list', () => {
    expect(validateLayout(valid({ currency: 'GBP' }))?.currency).toBeDefined();
  });
});

describe('toLayoutRequest', () => {
  it('trims, converts prices and uses the exact tier name for each section', () => {
    const request = toLayoutRequest(
      valid({ tiers: [{ name: ' Standard ', price: '1500.50' }, { name: 'VIP', price: '2' }] }),
    );

    expect(request.currency).toBe('UZS');
    expect(request.priceTiers).toEqual([
      { name: 'Standard', priceMinor: 150050 },
      { name: 'VIP', priceMinor: 200 },
    ]);
    expect(request.sections).toEqual([
      { name: 'Stalls', tier: 'Standard', rows: 3, seatsPerRow: 4 },
      { name: 'Front', tier: 'VIP', rows: 2, seatsPerRow: 5 },
    ]);
  });
});

describe('formFromLayout', () => {
  it('starts from the starter when nothing was saved', () => {
    const form = formFromLayout({ currency: null, priceTiers: [], sections: [], totalSeats: 0 });

    expect(form).toEqual(starterLayout());
  });

  it('shows a saved layout as typed values', () => {
    const form = formFromLayout({
      currency: 'USD',
      totalSeats: 12,
      priceTiers: [{ id: 't1', name: 'Standard', priceMinor: 1050 }],
      sections: [{ id: 's1', name: 'Stalls', tier: 'Standard', rows: 3, seatsPerRow: 4, seatCount: 12 }],
    });

    expect(form).toEqual({
      currency: 'USD',
      tiers: [{ name: 'Standard', price: '10.50' }],
      sections: [{ name: 'Stalls', tier: 'Standard', rows: '3', seatsPerRow: '4' }],
    });
  });
});

describe('layoutErrorsFromServer', () => {
  it('puts each problem on the tier or section it names', () => {
    const errors = layoutErrorsFromServer([
      { field: 'currency', problem: 'is required' },
      { field: 'priceTiers[1].priceMinor', problem: 'must be between 1 and 10' },
      { field: 'priceTiers[0].name', problem: 'must be unique' },
      { field: 'sections[2].tier', problem: 'must be the name of one of the price tiers' },
      { field: 'sections[0].seatsPerRow', problem: 'must be between 1 and 100' },
      { field: 'sections', problem: 'must have at most 5000 seats in total' },
    ]);

    expect(errors.currency).toBe('is required');
    expect(errors.tiers[1].price).toBe('must be between 1 and 10');
    expect(errors.tiers[0].name).toBe('must be unique');
    expect(errors.sections[2].tier).toMatch(/price tiers/);
    expect(errors.sections[0].seatsPerRow).toBeDefined();
    expect(errors.general).toEqual(['sections: must have at most 5000 seats in total']);
  });
});
