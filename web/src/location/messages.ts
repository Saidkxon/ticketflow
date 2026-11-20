import { ApiError } from '../api/problem';

export function messageForConsentError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'CONSENT_TEXT_OUTDATED') {
      return 'The consent text was updated. Reload the page to read the current text.';
    }
    if (error.code === 'RATE_LIMITED') {
      return 'Too many attempts. Please wait a moment and try again.';
    }
    if (error.code === 'UPSTREAM_UNAVAILABLE' || error.code === 'UPSTREAM_TIMEOUT') {
      return 'The service is not answering right now. Please try again in a moment.';
    }
  }
  return 'Your answer could not be saved. Please try again.';
}
