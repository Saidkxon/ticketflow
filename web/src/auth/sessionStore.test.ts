import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/problem';
import { ADA, fakeApi, token } from '../test/fakeApi';
import { createSessionStore } from './sessionStore';

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('restoring a session at page load', () => {
  it('signs the user in when the refresh cookie is valid', async () => {
    const api = fakeApi();
    api.refresh.mockResolvedValue(token('access-1'));
    const store = createSessionStore(api);

    await store.restore();

    expect(store.getState()).toEqual({ status: 'signedIn', user: ADA });
    expect(api.fetchMe).toHaveBeenCalledWith('access-1');
  });

  it('is signed out when there is no valid session', async () => {
    const api = fakeApi();
    api.refresh.mockRejectedValue(new ApiError(401, 'INVALID_REFRESH_TOKEN'));
    const store = createSessionStore(api);

    await store.restore();

    expect(store.getState()).toEqual({ status: 'signedOut' });
    expect(api.fetchMe).not.toHaveBeenCalled();
  });

  it('refreshes only once when asked twice (React runs effects twice in development)', async () => {
    const api = fakeApi();
    api.refresh.mockResolvedValue(token('access-1'));
    const store = createSessionStore(api);

    await Promise.all([store.restore(), store.restore()]);
    await store.restore();

    expect(api.refresh).toHaveBeenCalledTimes(1);
  });

  it('is signed out when the user cannot be loaded after the refresh', async () => {
    const api = fakeApi();
    api.refresh.mockResolvedValue(token('access-1'));
    api.fetchMe.mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR'));
    const store = createSessionStore(api);

    await store.restore();

    expect(store.getState()).toEqual({ status: 'signedOut' });
  });
});

describe('login, register and logout', () => {
  async function signedOutStore() {
    const api = fakeApi();
    const store = createSessionStore(api);
    await store.restore();
    return { api, store };
  }

  it('logs in and keeps the token out of state and out of browser storage', async () => {
    const { api, store } = await signedOutStore();
    api.login.mockResolvedValue(token('secret-access-token'));

    await store.login('ada@example.com', 'a long password');

    expect(api.login).toHaveBeenCalledWith({ email: 'ada@example.com', password: 'a long password' });
    expect(store.getState()).toEqual({ status: 'signedIn', user: ADA });
    expect(JSON.stringify(store.getState())).not.toContain('secret-access-token');
    expect(JSON.stringify({ ...localStorage })).not.toContain('secret-access-token');
    expect(JSON.stringify({ ...sessionStorage })).not.toContain('secret-access-token');
  });

  it('stays signed out and passes the error on when the login is refused', async () => {
    const { api, store } = await signedOutStore();
    api.login.mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS'));

    await expect(store.login('ada@example.com', 'wrong')).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' });

    expect(store.getState()).toEqual({ status: 'signedOut' });
  });

  it('registers and then signs in', async () => {
    const { api, store } = await signedOutStore();

    await store.register('ada@example.com', 'a long password', 'Ada Lovelace');

    expect(api.register).toHaveBeenCalledWith({
      email: 'ada@example.com',
      password: 'a long password',
      displayName: 'Ada Lovelace',
    });
    expect(api.login).toHaveBeenCalledTimes(1);
    expect(store.getState().status).toBe('signedIn');
  });

  it('does not try to sign in when registration fails', async () => {
    const { api, store } = await signedOutStore();
    api.register.mockRejectedValue(new ApiError(409, 'EMAIL_ALREADY_REGISTERED'));

    await expect(store.register('ada@example.com', 'a long password', 'Ada')).rejects.toBeInstanceOf(ApiError);

    expect(api.login).not.toHaveBeenCalled();
  });

  it('logs out', async () => {
    const { api, store } = await signedOutStore();
    await store.login('ada@example.com', 'a long password');

    await store.logout();

    expect(api.logout).toHaveBeenCalledTimes(1);
    expect(store.getState()).toEqual({ status: 'signedOut' });
  });

  it('ends the session even when the logout call fails', async () => {
    const { api, store } = await signedOutStore();
    await store.login('ada@example.com', 'a long password');
    api.logout.mockRejectedValue(new Error('network down'));

    await store.logout();

    expect(store.getState()).toEqual({ status: 'signedOut' });
  });

  it('tells subscribers about every change, and stops after unsubscribe', async () => {
    const { store } = await signedOutStore();
    const listener = vi.fn();
    const unsubscribe = store.subscribe(listener);

    await store.login('ada@example.com', 'a long password');
    expect(listener).toHaveBeenCalled();

    unsubscribe();
    listener.mockClear();
    await store.logout();
    expect(listener).not.toHaveBeenCalled();
  });
});

describe('refreshing before the access token expires', () => {
  it('refreshes a minute before expiry and uses the new token', async () => {
    const api = fakeApi();
    const store = createSessionStore(api);
    await store.restore();
    api.login.mockResolvedValue(token('access-1', 900));
    api.refresh.mockResolvedValue(token('access-2', 900));
    await store.login('ada@example.com', 'a long password');

    await vi.advanceTimersByTimeAsync(839_000);
    expect(api.refresh).toHaveBeenCalledTimes(1); // the one from restore()
    await vi.advanceTimersByTimeAsync(2_000);

    expect(api.refresh).toHaveBeenCalledTimes(2);
    expect(store.getState().status).toBe('signedIn');
  });

  it('signs out when the timed refresh is refused', async () => {
    const api = fakeApi();
    const store = createSessionStore(api);
    await store.restore();
    await store.login('ada@example.com', 'a long password');
    api.refresh.mockRejectedValue(new ApiError(401, 'INVALID_REFRESH_TOKEN'));

    await vi.advanceTimersByTimeAsync(841_000);

    expect(store.getState()).toEqual({ status: 'signedOut' });
  });

  it('stops the timer at logout', async () => {
    const api = fakeApi();
    const store = createSessionStore(api);
    await store.restore();
    await store.login('ada@example.com', 'a long password');
    await store.logout();
    api.refresh.mockClear();

    await vi.advanceTimersByTimeAsync(2_000_000);

    expect(api.refresh).not.toHaveBeenCalled();
  });
});

describe('authorizedFetch', () => {
  function stubFetch(handler: (authorization: string | null) => number) {
    const fetchMock = vi.fn((_input: unknown, init?: RequestInit) => {
      const authorization = new Headers(init?.headers).get('Authorization');
      return Promise.resolve(new Response('{}', { status: handler(authorization) }));
    });
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  }

  async function signedInStore() {
    const api = fakeApi();
    const store = createSessionStore(api);
    await store.restore();
    api.login.mockResolvedValue(token('old-token'));
    await store.login('ada@example.com', 'a long password');
    return { api, store };
  }

  it('sends the token', async () => {
    const { store } = await signedInStore();
    const fetchMock = stubFetch(() => 200);

    const response = await store.authorizedFetch('/api/v1/something');

    expect(response.status).toBe(200);
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get('Authorization')).toBe('Bearer old-token');
  });

  it('refreshes once and retries once after a 401', async () => {
    const { api, store } = await signedInStore();
    api.refresh.mockClear();
    api.refresh.mockResolvedValue(token('new-token'));
    const fetchMock = stubFetch((authorization) => (authorization === 'Bearer new-token' ? 200 : 401));

    const response = await store.authorizedFetch('/api/v1/something');

    expect(response.status).toBe(200);
    expect(api.refresh).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('shares one refresh between requests that fail at the same moment', async () => {
    const { api, store } = await signedInStore();
    api.refresh.mockClear();
    api.refresh.mockImplementation(
      () => new Promise((resolve) => setTimeout(() => resolve(token('new-token')), 50)),
    );
    stubFetch((authorization) => (authorization === 'Bearer new-token' ? 200 : 401));

    const responses = await Promise.all([
      store.authorizedFetch('/api/v1/a'),
      store.authorizedFetch('/api/v1/b'),
      store.authorizedFetch('/api/v1/c'),
      vi.advanceTimersByTimeAsync(100).then(() => null),
    ]);

    expect(api.refresh).toHaveBeenCalledTimes(1);
    expect(responses.slice(0, 3).map((response) => response?.status)).toEqual([200, 200, 200]);
  });

  it('signs out when the retry is refused too', async () => {
    const { api, store } = await signedInStore();
    api.refresh.mockResolvedValue(token('new-token'));
    stubFetch(() => 401);

    const response = await store.authorizedFetch('/api/v1/something');

    expect(response.status).toBe(401);
    expect(store.getState()).toEqual({ status: 'signedOut' });
  });

  it('signs out and returns the 401 when the refresh is refused', async () => {
    const { api, store } = await signedInStore();
    api.refresh.mockRejectedValue(new ApiError(401, 'INVALID_REFRESH_TOKEN'));
    stubFetch(() => 401);

    const response = await store.authorizedFetch('/api/v1/something');

    expect(response.status).toBe(401);
    expect(store.getState()).toEqual({ status: 'signedOut' });
  });

  it('refuses to call without a session', async () => {
    const api = fakeApi();
    const store = createSessionStore(api);
    await store.restore();
    const fetchMock = stubFetch(() => 200);

    await expect(store.authorizedFetch('/api/v1/something')).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
