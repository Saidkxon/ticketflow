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

  constructor(status: number, code: string, correlationId?: string, fieldErrors: FieldError[] = []) {
    super(`${status} ${code}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
    this.fieldErrors = fieldErrors;
  }
}

export async function toApiError(response: Response): Promise<ApiError> {
  const headerId = response.headers.get('X-Correlation-ID') ?? undefined;
  try {
    const problem = (await response.json()) as Partial<Problem>;
    return new ApiError(
      response.status,
      problem.code ?? 'UNKNOWN',
      problem.correlationId ?? headerId,
      problem.errors ?? [],
    );
  } catch {
    return new ApiError(response.status, 'UNKNOWN', headerId);
  }
}
