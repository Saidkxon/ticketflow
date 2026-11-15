import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/problem';
import { messageForAuthError, referenceOf } from './messages';

describe('referenceOf', () => {
  it('gives no reference for errors the page explains in words', () => {
    for (const code of ['INVALID_CREDENTIALS', 'EMAIL_ALREADY_REGISTERED', 'RATE_LIMITED', 'INVALID_REQUEST']) {
      expect(referenceOf(new ApiError(400, code, 'ref-1'))).toBeUndefined();
    }
  });

  it('gives the reference for an unexpected error', () => {
    expect(referenceOf(new ApiError(500, 'INTERNAL_ERROR', 'ref-1'))).toBe('ref-1');
    expect(referenceOf(new ApiError(502, 'UNKNOWN', 'ref-2'))).toBe('ref-2');
  });

  it('gives nothing when the server sent no reference or the error is not from the API', () => {
    expect(referenceOf(new ApiError(500, 'INTERNAL_ERROR'))).toBeUndefined();
    expect(referenceOf(new Error('network down'))).toBeUndefined();
  });
});

describe('messageForAuthError', () => {
  it('uses the general message for an unknown code', () => {
    expect(messageForAuthError(new ApiError(500, 'INTERNAL_ERROR'))).toBe('Something went wrong. Please try again.');
  });
});
