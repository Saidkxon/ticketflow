/**
 * The Google Maps browser key (ADR-0015, L9). It comes from the build setting VITE_GOOGLE_MAPS_API_KEY and is never in
 * the repository. A browser key is visible to anybody who opens the page, so its protection is the restriction set in the
 * Google console: our site addresses only, the Maps JavaScript API only, a daily quota and a budget alert.
 */
export function mapsApiKey(): string {
  const value: unknown = import.meta.env.VITE_GOOGLE_MAPS_API_KEY;
  return typeof value === 'string' ? value.trim() : '';
}
