import type { components } from './catalog.schema';

type Problem = components['schemas']['Problem'];

/** One field that the server did not accept (ADR-0009, part B). */
export interface FieldError {
  field: string;
  problem: string;
}

/** An error answer from the API (RFC 9457 problem). The UI switches on `code`, never on the text (ADR-0009). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId?: string;
  readonly fieldErrors: FieldError[];
  /** From the Retry-After header of a 429 or 503: how long the server asks the client to wait. */
  readonly retryAfterSeconds?: number;

  constructor(
    status: number,
    code: string,
    correlationId?: string,
    fieldErrors: FieldError[] = [],
    retryAfterSeconds?: number,
  ) {
    super(`${status} ${code}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
    this.fieldErrors = fieldErrors;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/** Retry-After as whole seconds. A date value, a negative or a silly number is ignored. */
function retryAfterOf(response: Response): number | undefined {
  const value = response.headers.get('Retry-After');
  if (value === null || !/^\d{1,6}$/.test(value.trim())) {
    return undefined;
  }
  return Number(value.trim());
}

export async function toApiError(response: Response): Promise<ApiError> {
  const headerId = response.headers.get('X-Correlation-ID') ?? undefined;
  const retryAfter = retryAfterOf(response);
  try {
    const problem = (await response.json()) as Partial<Problem>;
    return new ApiError(
      response.status,
      problem.code ?? 'UNKNOWN',
      problem.correlationId ?? headerId,
      problem.errors ?? [],
      retryAfter,
    );
  } catch {
    return new ApiError(response.status, 'UNKNOWN', headerId, [], retryAfter);
  }
}
