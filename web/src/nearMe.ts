/**
 * The point of a "Near me" search is rounded to two decimals (about one kilometre) before it leaves the browser. The
 * search needs no more, and the point is part of a request address that servers in between may write to their logs
 * (ADR-0015, "Near me" clarification).
 */
export function roundForSearch(coordinate: number): number {
  return Math.round(coordinate * 100) / 100;
}

/** "about 12 km away". The point is rounded to about a kilometre, so a finer distance would be false precision. */
export function describeDistance(distanceKm: number): string {
  return distanceKm < 1 ? 'less than 1 km away' : `about ${Math.round(distanceKm)} km away`;
}

export const RADIUS_CHOICES_KM = [10, 25, 50, 100, 500] as const;
export const DEFAULT_RADIUS_KM = 50;
