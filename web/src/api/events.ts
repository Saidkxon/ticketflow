import type { components } from './catalog.schema';
import { toApiError } from './problem';

// Types come from contracts/catalog.openapi.yaml (run "npm run generate:api" after a contract change).
export type EventPage = components['schemas']['EventPage'];
export type EventSummary = components['schemas']['EventSummary'];
export { ApiError } from './problem';

export async function fetchEvents(page = 0, size = 20): Promise<EventPage> {
  const response = await fetch(`/api/v1/events?page=${page}&size=${size}`);
  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as EventPage;
}
