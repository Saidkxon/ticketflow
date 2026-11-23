import { ApiError } from './problem';

/**
 * Whether a failed read is tried again. An answer like 404 or 400 will not change by asking again, so the person
 * sees it at once. Network errors and server errors (5xx, 429) are tried twice more, as they may be short.
 */
export function shouldRetry(failureCount: number, error: Error): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 429) {
    return false;
  }
  return failureCount < 2;
}
