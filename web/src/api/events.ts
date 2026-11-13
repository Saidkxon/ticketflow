import type { components } from './catalog.schema';

// Types come from contracts/catalog.openapi.yaml (run "npm run generate:api" after a contract change).
export type EventPage = components['schemas']['EventPage'];
export type EventSummary = components['schemas']['EventSummary'];
type Problem = components['schemas']['Problem'];

/** An error answer from the API (RFC 9457 problem). The UI switches on `code`, never on the text (ADR-0009). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId?: string;

  constructor(status: number, code: string, correlationId?: string) {
    super(`${status} ${code}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
  }
}

export async function fetchEvents(page = 0, size = 20): Promise<EventPage> {
  const response = await fetch(`/api/v1/events?page=${page}&size=${size}`);
  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as EventPage;
}

async function toApiError(response: Response): Promise<ApiError> {
  const headerId = response.headers.get('X-Correlation-ID') ?? undefined;
  try {
    const problem = (await response.json()) as Partial<Problem>;
    return new ApiError(response.status, problem.code ?? 'UNKNOWN', problem.correlationId ?? headerId);
  } catch {
    return new ApiError(response.status, 'UNKNOWN', headerId);
  }
}
