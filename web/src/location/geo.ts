/** A person has to move about this far before another point is sent (ADR-0015, L8). */
export const MIN_DISTANCE_METERS = 200;
/** And at most one point is sent per this time. */
export const MIN_INTERVAL_MS = 60_000;

export interface Fix {
  latitude: number;
  longitude: number;
  /** When the browser read the position, milliseconds since 1970. */
  atMs: number;
}

const EARTH_RADIUS_METERS = 6_371_000;

/** Great-circle distance (haversine). Accurate to well under a percent at these distances. */
export function distanceMeters(a: Fix, b: Fix): number {
  const toRadians = (degrees: number) => (degrees * Math.PI) / 180;
  const dLat = toRadians(b.latitude - a.latitude);
  const dLng = toRadians(b.longitude - a.longitude);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRadians(a.latitude)) * Math.cos(toRadians(b.latitude)) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** The first reading is always sent; after that, only a reading that is both a minute later and 200 m away. */
export function shouldSend(last: Fix | null, next: Fix): boolean {
  if (last === null) {
    return true;
  }
  if (next.atMs - last.atMs < MIN_INTERVAL_MS) {
    return false;
  }
  return distanceMeters(last, next) >= MIN_DISTANCE_METERS;
}
