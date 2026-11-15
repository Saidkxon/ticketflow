import type { components } from './identity.schema';
import { toApiError } from './problem';

// Types come from contracts/identity.openapi.yaml (run "npm run generate:api" after a contract change).
export type User = components['schemas']['User'];
export type TokenResponse = components['schemas']['TokenResponse'];
export type RegisterRequest = components['schemas']['RegisterRequest'];
export type LoginRequest = components['schemas']['LoginRequest'];

const JSON_HEADERS = { 'Content-Type': 'application/json' };
/** Required by refresh and logout: a page of another origin cannot add a custom header (ADR-0004). */
const CLIENT_HEADER = { 'X-TicketFlow-Client': 'web' };

async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as T;
}

export async function register(body: RegisterRequest): Promise<User> {
  const response = await fetch('/api/v1/auth/register', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(body),
  });
  return readJson<User>(response);
}

export async function login(body: LoginRequest): Promise<TokenResponse> {
  const response = await fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(body),
  });
  return readJson<TokenResponse>(response);
}

/** The browser sends the refresh cookie by itself; script never sees it. */
export async function refresh(): Promise<TokenResponse> {
  const response = await fetch('/api/v1/auth/refresh', { method: 'POST', headers: CLIENT_HEADER });
  return readJson<TokenResponse>(response);
}

export async function logout(): Promise<void> {
  const response = await fetch('/api/v1/auth/logout', { method: 'POST', headers: CLIENT_HEADER });
  if (!response.ok) {
    throw await toApiError(response);
  }
}

export async function fetchMe(accessToken: string): Promise<User> {
  const response = await fetch('/api/v1/users/me', { headers: { Authorization: `Bearer ${accessToken}` } });
  return readJson<User>(response);
}

export interface AuthApi {
  register: typeof register;
  login: typeof login;
  refresh: typeof refresh;
  logout: typeof logout;
  fetchMe: typeof fetchMe;
}

export const authApi: AuthApi = { register, login, refresh, logout, fetchMe };
