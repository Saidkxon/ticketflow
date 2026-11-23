const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });

/**
 * An amount in minor units as a person reads it: 20000000 and "UZS" give "200,000 UZS". Every currency we support has
 * two decimals (the organizer form says the same). The amounts are far below 2^53, so the division is exact enough
 * for display; the server does every real calculation.
 */
export function formatMoney(amountMinor: number, currency: string): string {
  return `${number.format(amountMinor / 100)} ${currency}`;
}
