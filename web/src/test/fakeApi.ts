import { vi, type Mock } from 'vitest';
import type { AuthApi, TokenResponse, User } from '../api/auth';

export type FakeApi = { [K in keyof AuthApi]: Mock<AuthApi[K]> };

export const ADA: User = {
  id: '6f1c2b0e-7d5a-4b9e-8c11-2a3b4c5d6e7f',
  email: 'ada@example.com',
  displayName: 'Ada Lovelace',
  roles: ['CUSTOMER'],
  createdAt: '2026-11-15T10:00:00Z',
};

export function token(value: string, expiresIn = 900): TokenResponse {
  return { accessToken: value, tokenType: 'Bearer', expiresIn };
}

/** A stand-in for the Identity API. By default, nobody has a session and every call works. */
export function fakeApi(): FakeApi {
  const api: FakeApi = {
    register: vi.fn<AuthApi['register']>().mockResolvedValue(ADA),
    login: vi.fn<AuthApi['login']>().mockResolvedValue(token('access-1')),
    refresh: vi.fn<AuthApi['refresh']>().mockRejectedValue(new Error('no session')),
    logout: vi.fn<AuthApi['logout']>().mockResolvedValue(undefined),
    fetchMe: vi.fn<AuthApi['fetchMe']>().mockResolvedValue(ADA),
  };
  return api;
}
