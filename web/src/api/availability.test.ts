import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchAvailability } from './availability';
import { ApiError } from './problem';

afterEach(() => {
  vi.unstubAllGlobals();
});

function stub(response: Response) {
  const mock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', mock);
  return mock;
}

describe('fetchAvailability', () => {
  it('returns the list and its ETag', async () => {
    const mock = stub(
      new Response(JSON.stringify({ eventId: 'e1', unavailableSeatIds: ['a', 'b'] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json', ETag: 'W/"abc"' },
      }),
    );
    const result = await fetchAvailability('e1', null);
    expect(result).toEqual({ kind: 'changed', etag: 'W/"abc"', unavailableSeatIds: ['a', 'b'] });
    expect(mock.mock.calls[0][0]).toBe('/api/v1/events/e1/availability');
    expect(mock.mock.calls[0][1].headers).toEqual({});
  });

  it('sends the ETag it has and understands 304', async () => {
    const mock = stub(new Response(null, { status: 304 }));
    const result = await fetchAvailability('e1', 'W/"abc"');
    expect(result).toEqual({ kind: 'unchanged' });
    expect(mock.mock.calls[0][1].headers).toEqual({ 'If-None-Match': 'W/"abc"' });
  });

  it('turns a 429 into an ApiError that carries Retry-After', async () => {
    stub(
      new Response(JSON.stringify({ code: 'RATE_LIMITED', correlationId: 'c1' }), {
        status: 429,
        headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '17' },
      }),
    );
    const error = await fetchAvailability('e1', null).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 429, code: 'RATE_LIMITED', retryAfterSeconds: 17 });
  });

  it('ignores a Retry-After that is not a number of seconds', async () => {
    stub(
      new Response(JSON.stringify({ code: 'UPSTREAM_UNAVAILABLE' }), {
        status: 503,
        headers: { 'Content-Type': 'application/problem+json', 'Retry-After': 'Wed, 21 Oct 2026 07:28:00 GMT' },
      }),
    );
    const error = (await fetchAvailability('e1', null).catch((e: unknown) => e)) as ApiError;
    expect(error.retryAfterSeconds).toBeUndefined();
  });

  it('passes the abort signal on', async () => {
    const mock = stub(new Response(null, { status: 304 }));
    const controller = new AbortController();
    await fetchAvailability('e1', null, controller.signal);
    expect(mock.mock.calls[0][1].signal).toBe(controller.signal);
  });
});
