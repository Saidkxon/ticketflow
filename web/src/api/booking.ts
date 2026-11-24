import type { components } from './booking.schema';
import { toApiError } from './problem';

// Types come from contracts/booking.openapi.yaml (run "npm run generate:api" after a contract change).
export type Booking = components['schemas']['Booking'];
export type BookingSeat = components['schemas']['BookingSeat'];
export type BookingPage = components['schemas']['BookingPage'];

/** fetch with the access token; the session store provides it (ADR-0004). */
export type Fetcher = (input: string, init?: RequestInit) => Promise<Response>;

/** The newest bookings the lookup of an active hold reads (the server allows at most 50 per page). */
const LOOKUP_PAGE_SIZE = 50;

async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as T;
}

/**
 * Holds seats (ADR-0012). The Idempotency-Key makes a retry safe: the same key with the same seats answers with the
 * same booking and holds nothing twice (ADR-0009, part C).
 */
export async function holdSeats(
  fetcher: Fetcher,
  eventId: string,
  seatIds: readonly string[],
  idempotencyKey: string,
): Promise<Booking> {
  return readJson<Booking>(
    await fetcher('/api/v1/bookings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ eventId, seatIds }),
    }),
  );
}

/** Gives the seats of a hold back. Repeating it is fine: a released hold answers 200 again. */
export async function releaseHold(fetcher: Fetcher, bookingId: string): Promise<Booking> {
  return readJson<Booking>(await fetcher(`/api/v1/bookings/${encodeURIComponent(bookingId)}/release`, { method: 'POST' }));
}

/**
 * The customer's live hold on an event, or null. A hold whose deadline has passed is already EXPIRED in the answer, so
 * HELD means it still runs. Booking has no filter by event yet, so this reads the newest bookings of the customer; a
 * hold is always among the newest unless 50 later bookings exist (a filter on the server is on the Phase 5 list).
 */
export async function findActiveHold(fetcher: Fetcher, eventId: string, signal?: AbortSignal): Promise<Booking | null> {
  const page = await readJson<BookingPage>(await fetcher(`/api/v1/bookings?page=0&size=${LOOKUP_PAGE_SIZE}`, { signal }));
  return page.items.find((booking) => booking.eventId === eventId && booking.status === 'HELD') ?? null;
}
