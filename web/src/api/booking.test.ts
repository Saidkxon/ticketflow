import { afterEach, describe, expect, it, vi } from 'vitest';
import { findActiveHold, holdSeats, releaseHold, type Booking } from './booking';
import { ApiError } from './problem';

const held = (id: string, eventId: string, status: string) => ({ id, eventId, status }) as Booking;
const ok = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });

afterEach(() => vi.restoreAllMocks());

describe('booking API', () => {
  it('sends a hold with the key, the event and the seats', async () => {
    const fetcher = vi.fn().mockResolvedValue(ok({ id: 'b1' }));
    await holdSeats(fetcher, 'event-1', ['a', 'b'], 'key-1');
    const [url, init] = fetcher.mock.calls[0];
    expect(url).toBe('/api/v1/bookings');
    expect(init.method).toBe('POST');
    expect(init.headers['Idempotency-Key']).toBe('key-1');
    expect(JSON.parse(init.body)).toEqual({ eventId: 'event-1', seatIds: ['a', 'b'] });
  });

  it('reads the seats of a refused hold from the problem', async () => {
    const body = { type: 'about:blank', title: 't', status: 409, code: 'SEATS_UNAVAILABLE', unavailableSeatIds: ['a', 7, 'c'] };
    const fetcher = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(body), { status: 409, headers: { 'Content-Type': 'application/problem+json' } }),
    );
    const error = await holdSeats(fetcher, 'event-1', ['a'], 'k').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('SEATS_UNAVAILABLE');
    expect((error as ApiError).unavailableSeatIds).toEqual(['a', 'c']);
  });

  it('releases a hold by its ID', async () => {
    const fetcher = vi.fn().mockResolvedValue(ok({ id: 'b/1' }));
    await releaseHold(fetcher, 'b/1');
    expect(fetcher.mock.calls[0][0]).toBe('/api/v1/bookings/b%2F1/release');
    expect(fetcher.mock.calls[0][1].method).toBe('POST');
  });

  it('finds the running hold of this event only', async () => {
    const items = [held('1', 'event-2', 'HELD'), held('2', 'event-1', 'EXPIRED'), held('3', 'event-1', 'HELD')];
    const fetcher = vi.fn().mockResolvedValue(ok({ items, page: 0, size: 50, totalItems: 3 }));
    expect((await findActiveHold(fetcher, 'event-1'))?.id).toBe('3');
    expect(fetcher.mock.calls[0][0]).toBe('/api/v1/bookings?page=0&size=50');
    fetcher.mockResolvedValue(ok({ items: items.slice(0, 2), page: 0, size: 50, totalItems: 2 }));
    expect(await findActiveHold(fetcher, 'event-1')).toBeNull();
  });
});
