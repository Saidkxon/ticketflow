import type { components } from './identity.schema';
import type { Fetcher } from './organizer';
import { toApiError } from './problem';

// Types come from contracts/identity.openapi.yaml (run "npm run generate:api" after a contract change).
export type LocationConsent = components['schemas']['LocationConsent'];
export type LocationLevel = components['schemas']['LocationLevel'];
export type PositionRequest = components['schemas']['PositionRequest'];

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export async function getConsent(fetcher: Fetcher): Promise<LocationConsent> {
  const response = await fetcher('/api/v1/location/consent');
  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as LocationConsent;
}

/** textVersion is the version of the consent text the person was shown (see consentText.tsx). */
export async function saveConsent(
  fetcher: Fetcher,
  level: LocationLevel,
  textVersion: number,
): Promise<LocationConsent> {
  const response = await fetcher('/api/v1/location/consent', {
    method: 'PUT',
    headers: JSON_HEADERS,
    body: JSON.stringify({ level, textVersion }),
  });
  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as LocationConsent;
}

/** 201 (stored) and 202 (ignored, too soon after the previous point) are both fine. */
export async function sendPosition(fetcher: Fetcher, body: PositionRequest): Promise<void> {
  const response = await fetcher('/api/v1/location/positions', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw await toApiError(response);
  }
}
