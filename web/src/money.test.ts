import { describe, expect, it } from 'vitest';
import { formatMoney } from './money';

describe('formatMoney', () => {
  it('shows major units with the currency code', () => {
    expect(formatMoney(20000000, 'UZS').replace(/\s/g, ' ')).toMatch(/^200[,.\s ]?000 UZS$/);
  });

  it('keeps the cents and drops needless zeros', () => {
    expect(formatMoney(150050, 'USD')).toMatch(/^1[,.\s ]?500[.,]5 USD$/);
    expect(formatMoney(5, 'USD')).toMatch(/^0[.,]05 USD$/);
    expect(formatMoney(0, 'USD')).toBe('0 USD');
  });
});
