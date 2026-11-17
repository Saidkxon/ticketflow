import { ApiError } from '../api/problem';

/** Words for the codes a person can act on (ADR-0009: the UI switches on `code`, never on server text). */
export function messageForAuthError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'INVALID_CREDENTIALS':
        return 'The email or password does not match.';
      case 'EMAIL_ALREADY_REGISTERED':
        return 'An account with this email already exists. Try signing in instead.';
      case 'RATE_LIMITED':
        return 'Too many attempts. Please wait a moment and try again.';
      case 'INVALID_REQUEST':
        return 'Please check the fields below.';
      case 'UPSTREAM_UNAVAILABLE':
      case 'UPSTREAM_TIMEOUT':
        return 'The service is not answering right now. Please try again in a moment.';
    }
  }
  return 'Something went wrong. Please try again.';
}

/** Codes the pages explain in words. The person can act on them, so a reference would only be noise. */
const EXPLAINED_CODES = new Set([
  'INVALID_CREDENTIALS',
  'EMAIL_ALREADY_REGISTERED',
  'RATE_LIMITED',
  'INVALID_REQUEST',
  'UPSTREAM_UNAVAILABLE',
  'UPSTREAM_TIMEOUT',
]);

/**
 * The reference (the correlation ID) a person can quote to support, shown only when something unexpected
 * happened and the server gave one. Expected errors, such as a wrong password, get no reference.
 */
export function referenceOf(error: unknown): string | undefined {
  if (error instanceof ApiError && !EXPLAINED_CODES.has(error.code)) {
    return error.correlationId;
  }
  return undefined;
}
