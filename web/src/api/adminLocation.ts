import type { components } from './identity.schema';
import type { Fetcher } from './organizer';
import { toApiError } from './problem';

// Types come from contracts/identity.openapi.yaml (run "npm run generate:api" after a contract change).
export type LatestPosition = components['schemas']['LatestPosition'];
export type LatestPositions = components['schemas']['LatestPositions'];
export type HistoryPoint = components['schemas']['HistoryPoint'];
export type UserLocationHistory = components['schemas']['UserLocationHistory'];

async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as T;
}

/** The newest point of every consenting user. Every call is written to the access log on the server (ADR-0015). */
export async function getLatestPositions(fetcher: Fetcher): Promise<LatestPositions> {
  return readJson<LatestPositions>(await fetcher('/api/v1/admin/location/latest'));
}

/** One user's points, newest first. Also written to the access log. */
export async function getUserHistory(fetcher: Fetcher, userId: string, limit: number): Promise<UserLocationHistory> {
  return readJson<UserLocationHistory>(
    await fetcher(`/api/v1/admin/location/users/${encodeURIComponent(userId)}/history?limit=${limit}`),
  );
}
