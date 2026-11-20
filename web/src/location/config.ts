/**
 * The location question is mandatory (ADR-0015, decided by the instructor on 2026-11-20). The one switch that
 * brings the "Not now" link back is the build setting VITE_LOCATION_ALLOW_NOT_NOW=true. It is read when the
 * function is called, so a test can change it. Any other value, or no value, means the question cannot be skipped.
 */
export function allowNotNow(): boolean {
  return import.meta.env.VITE_LOCATION_ALLOW_NOT_NOW === 'true';
}
