import type { components } from './catalog.schema';
import { toApiError } from './problem';

// Types come from contracts/catalog.openapi.yaml (run "npm run generate:api" after a contract change).
export type EventDetail = components['schemas']['EventDetail'];
export type MyEventPage = components['schemas']['MyEventPage'];
export type SaveEventRequest = components['schemas']['SaveEventRequest'];

/** fetch with the access token; the session store provides it (ADR-0004). */
export type Fetcher = (input: string, init?: RequestInit) => Promise<Response>;

const JSON_HEADERS = { 'Content-Type': 'application/json' };

async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as T;
}

/** The ETag header is the version in quotes; the event body carries the same number. */
export function ifMatch(version: number): string {
  return `"${version}"`;
}

export async function listMyEvents(fetcher: Fetcher, page: number, size: number): Promise<MyEventPage> {
  return readJson<MyEventPage>(await fetcher(`/api/v1/events/mine?page=${page}&size=${size}`));
}

export async function getMyEvent(fetcher: Fetcher, eventId: string): Promise<EventDetail> {
  return readJson<EventDetail>(await fetcher(`/api/v1/events/mine/${encodeURIComponent(eventId)}`));
}

export async function createEvent(fetcher: Fetcher, body: SaveEventRequest): Promise<EventDetail> {
  return readJson<EventDetail>(
    await fetcher('/api/v1/events', { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }),
  );
}

/** Needs the version the person last saw (If-Match), so a change made elsewhere is never overwritten silently. */
export async function updateEvent(
  fetcher: Fetcher,
  eventId: string,
  version: number,
  body: SaveEventRequest,
): Promise<EventDetail> {
  return readJson<EventDetail>(
    await fetcher(`/api/v1/events/${encodeURIComponent(eventId)}`, {
      method: 'PUT',
      headers: { ...JSON_HEADERS, 'If-Match': ifMatch(version) },
      body: JSON.stringify(body),
    }),
  );
}
