import type { components } from './catalog.schema';
import { toApiError } from './problem';

// Types come from contracts/catalog.openapi.yaml (run "npm run generate:api" after a contract change).
export type EventPage = components['schemas']['EventPage'];
export type EventSummary = components['schemas']['EventSummary'];
export type PublishedLayout = components['schemas']['PublishedLayout'];
export { ApiError } from './problem';

/** A "near me" search: the point of the visitor and how far to look. Used for this request only (ADR-0015). */
export interface NearSearch {
  latitude: number;
  longitude: number;
  radiusKm: number;
}

export async function fetchEvents(page = 0, size = 20, near?: NearSearch): Promise<EventPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (near) {
    query.set('nearLatitude', String(near.latitude));
    query.set('nearLongitude', String(near.longitude));
    query.set('radiusKm', String(near.radiusKm));
  }
  const response = await fetch(`/api/v1/events?${query}`);
  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as EventPage;
}

/** One published event. A draft or unknown event is a 404 NOT_FOUND. */
export async function fetchEvent(eventId: string, signal?: AbortSignal): Promise<EventSummary> {
  const response = await fetch(`/api/v1/events/${encodeURIComponent(eventId)}`, { signal });
  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as EventSummary;
}

/** The frozen layout of a published event: every seat with its ID. It never changes, so it is fetched once. */
export async function fetchLayout(eventId: string, signal?: AbortSignal): Promise<PublishedLayout> {
  const response = await fetch(`/api/v1/events/${encodeURIComponent(eventId)}/layout`, { signal });
  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as PublishedLayout;
}
