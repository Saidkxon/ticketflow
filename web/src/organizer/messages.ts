import { ApiError } from '../api/problem';

/** Words for the codes an organizer can act on (ADR-0009: the UI switches on `code`, never on server text). */
export function messageForOrganizerError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'UNAUTHENTICATED':
        return 'Your session has ended. Please sign in again.';
      case 'FORBIDDEN':
        return 'Your account is not allowed to do this. Organizer access is given by an administrator.';
      case 'NOT_FOUND':
        return 'This event was not found.';
      case 'PRECONDITION_FAILED':
        return 'Someone else changed this event after you opened it. Reload to see the latest version, then make your change again.';
      case 'EVENT_NOT_EDITABLE':
        return 'This event is published and can no longer be edited.';
      case 'INVALID_REQUEST':
        return 'Please check the fields below.';
      case 'RATE_LIMITED':
        return 'Too many requests. Please wait a moment and try again.';
      case 'UPSTREAM_UNAVAILABLE':
      case 'UPSTREAM_TIMEOUT':
        return 'The event service is not answering right now. Please try again in a moment.';
    }
  }
  return 'Something went wrong. Please try again.';
}

/** A reference to quote to support, only when something unexpected happened. */
export function referenceFor(error: unknown): string | undefined {
  if (error instanceof ApiError && (error.status >= 500 || error.code === 'UNKNOWN')) {
    return error.correlationId;
  }
  return undefined;
}
