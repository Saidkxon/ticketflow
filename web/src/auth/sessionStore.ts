import { ApiError } from '../api/problem';
import { authApi, type AuthApi, type TokenResponse, type User } from '../api/auth';

export type SessionState =
  | { status: 'loading' } // looking for a session at page load
  | { status: 'signedOut' }
  | { status: 'signedIn'; user: User };

export interface SessionStore {
  getState(): SessionState;
  subscribe(listener: () => void): () => void;
  /** Looks for a session at page load. Safe to call more than once: it runs one time. */
  restore(): Promise<void>;
  login(email: string, password: string): Promise<void>;
  register(email: string, password: string, displayName: string): Promise<void>;
  logout(): Promise<void>;
  /** fetch with the access token; on a 401 it refreshes once and retries once. */
  authorizedFetch(input: string, init?: RequestInit): Promise<Response>;
}

/**
 * The signed-in session (ADR-0004). The access token lives only in this closure, in memory: it is
 * not in localStorage, not in state that components can read, and it is gone on a page reload; the
 * HttpOnly refresh cookie brings the session back after a reload.
 *
 * Refreshing happens in exactly one place. The server treats a refresh token that is used twice as
 * theft and ends the whole session, so two refresh calls must never run at the same moment (for
 * example two requests that both got a 401, or React starting an effect twice in development). Every
 * caller shares the one call that is in flight.
 */
export function createSessionStore(api: AuthApi = authApi, refreshMarginSeconds = 60): SessionStore {
  let state: SessionState = { status: 'loading' };
  let accessToken: string | null = null;
  let refreshTimer: ReturnType<typeof setTimeout> | undefined;
  let inFlightRefresh: Promise<string | null> | null = null;
  let restoring: Promise<void> | null = null;
  const listeners = new Set<() => void>();

  function setState(next: SessionState) {
    state = next;
    listeners.forEach((listener) => listener());
  }

  function endSession() {
    clearTimeout(refreshTimer);
    accessToken = null;
    setState({ status: 'signedOut' });
  }

  function keep(token: TokenResponse) {
    accessToken = token.accessToken;
    clearTimeout(refreshTimer);
    // Refresh a little before the access token expires, so a user who stays on the page is not interrupted.
    const delaySeconds = Math.max(5, token.expiresIn - refreshMarginSeconds);
    refreshTimer = setTimeout(() => {
      void refreshShared();
    }, delaySeconds * 1000);
  }

  function refreshShared(): Promise<string | null> {
    if (inFlightRefresh) {
      return inFlightRefresh;
    }
    inFlightRefresh = (async () => {
      try {
        const token = await api.refresh();
        keep(token);
        return token.accessToken;
      } catch {
        // Any failure ends the session: a refresh that may have been used up cannot safely be repeated.
        endSession();
        return null;
      } finally {
        inFlightRefresh = null;
      }
    })();
    return inFlightRefresh;
  }

  async function signIn(token: TokenResponse) {
    keep(token);
    try {
      setState({ status: 'signedIn', user: await api.fetchMe(token.accessToken) });
    } catch (error) {
      endSession();
      throw error;
    }
  }

  async function login(email: string, password: string) {
    await signIn(await api.login({ email, password }));
  }

  return {
    getState: () => state,
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    restore() {
      restoring ??= (async () => {
        const token = await refreshShared();
        if (token === null) {
          return; // refreshShared already set the state to signedOut
        }
        try {
          setState({ status: 'signedIn', user: await api.fetchMe(token) });
        } catch {
          endSession();
        }
      })();
      return restoring;
    },
    login,
    async register(email, password, displayName) {
      await api.register({ email, password, displayName });
      await login(email, password);
    },
    async logout() {
      try {
        await api.logout();
      } catch {
        // The session ends here in any case; the server side expires by itself.
      }
      endSession();
    },
    async authorizedFetch(input, init) {
      if (accessToken === null) {
        throw new ApiError(401, 'UNAUTHENTICATED');
      }
      const send = (token: string) => {
        const headers = new Headers(init?.headers);
        headers.set('Authorization', `Bearer ${token}`);
        return fetch(input, { ...init, headers });
      };
      let response = await send(accessToken);
      if (response.status === 401) {
        const fresh = await refreshShared();
        if (fresh === null) {
          return response;
        }
        response = await send(fresh);
        if (response.status === 401) {
          endSession();
        }
      }
      return response;
    },
  };
}
