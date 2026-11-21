import { ApiError } from '../api/problem';

export function messageForAdminError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'UNAUTHENTICATED':
        return 'Your session has ended. Please sign in again.';
      case 'FORBIDDEN':
        return 'Your account is not allowed to read location data.';
      case 'LOCATION_NOT_AVAILABLE':
        return 'This user has no location data to show.';
      case 'RATE_LIMITED':
        return 'Too many requests. Please wait a moment and try again.';
      case 'UPSTREAM_UNAVAILABLE':
      case 'UPSTREAM_TIMEOUT':
        return 'The service is not answering right now. Please try again in a moment.';
    }
  }
  return 'Something went wrong. Please try again.';
}
