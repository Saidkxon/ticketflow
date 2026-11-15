import { createContext, useContext, useEffect, useSyncExternalStore, type ReactNode } from 'react';
import { createSessionStore, type SessionState, type SessionStore } from './sessionStore';

const SessionContext = createContext<SessionStore | null>(null);

/** One store for the whole app. Tests pass their own store. */
const appStore = createSessionStore();

export function SessionProvider({ store = appStore, children }: { store?: SessionStore; children: ReactNode }) {
  useEffect(() => {
    void store.restore();
  }, [store]);
  return <SessionContext.Provider value={store}>{children}</SessionContext.Provider>;
}

export function useSessionStore(): SessionStore {
  const store = useContext(SessionContext);
  if (store === null) {
    throw new Error('useSessionStore must be used inside SessionProvider');
  }
  return store;
}

export function useSessionState(): SessionState {
  const store = useSessionStore();
  return useSyncExternalStore(store.subscribe, store.getState);
}
