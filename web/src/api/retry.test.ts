import { describe, expect, it } from 'vitest';
import { ApiError } from './problem';
import { shouldRetry } from './retry';

describe('shouldRetry', () => {
  it('does not ask again after a client error, which would give the same answer', () => {
    expect(shouldRetry(0, new ApiError(404, 'NOT_FOUND'))).toBe(false);
    expect(shouldRetry(0, new ApiError(400, 'INVALID_REQUEST'))).toBe(false);
    expect(shouldRetry(0, new ApiError(401, 'UNAUTHENTICATED'))).toBe(false);
  });

  it('asks again twice after a server error, too many requests or a network error', () => {
    for (const error of [new ApiError(503, 'UPSTREAM_UNAVAILABLE'), new ApiError(429, 'RATE_LIMITED'), new TypeError('network')]) {
      expect(shouldRetry(0, error)).toBe(true);
      expect(shouldRetry(1, error)).toBe(true);
      expect(shouldRetry(2, error)).toBe(false);
    }
  });
});
