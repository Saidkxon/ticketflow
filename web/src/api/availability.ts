import type { components } from './booking.schema';
import { toApiError } from './problem';

// The type comes from contracts/booking.openapi.yaml (run "npm run generate:api" after a contract change).
export type SeatAvailability = components['schemas']['SeatAvailability'];

/** "changed": a new list (with its ETag). "unchanged": the 304 answer, the list the caller already has is current. */
export type AvailabilityResult =
  | { kind: 'changed'; etag: string | null; unavailableSeatIds: string[] }
  | { kind: 'unchanged' };

/** Reads the unavailable seats of an event (ADR-0011). Public; with the ETag of the last list a 304 costs almost nothing. */
export async function fetchAvailability(
  eventId: string,
  etag: string | null,
  signal?: AbortSignal,
): Promise<AvailabilityResult> {
  const response = await fetch(`/api/v1/events/${encodeURIComponent(eventId)}/availability`, {
    headers: etag === null ? {} : { 'If-None-Match': etag },
    signal,
  });
  if (response.status === 304) {
    return { kind: 'unchanged' };
  }
  if (!response.ok) {
    throw await toApiError(response);
  }
  const body = (await response.json()) as SeatAvailability;
  return { kind: 'changed', etag: response.headers.get('ETag'), unavailableSeatIds: body.unavailableSeatIds };
}
