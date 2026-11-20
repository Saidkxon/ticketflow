import { useState } from 'react';
import { Link, Outlet } from 'react-router';
import { useSessionState, useSessionStore } from './auth/SessionContext';
import { LocationGate } from './location/LocationGate';

export function Layout() {
  const session = useSessionState();
  const store = useSessionStore();
  const [leaving, setLeaving] = useState(false);

  async function signOut() {
    setLeaving(true);
    try {
      await store.logout();
    } finally {
      setLeaving(false);
    }
  }

  return (
    <>
      <header className="site-header">
        <Link to="/" className="brand">
          TicketFlow
        </Link>
        <nav aria-label="Account">
          {session.status === 'signedIn' && (
            <>
              {session.user.roles.includes('ORGANIZER') && <Link to="/organizer">My events</Link>}
              <Link to="/account">{session.user.displayName}</Link>
              <button type="button" onClick={() => void signOut()} disabled={leaving}>
                Sign out
              </button>
            </>
          )}
          {session.status === 'signedOut' && (
            <>
              <Link to="/login">Sign in</Link>
              <Link to="/register">Create account</Link>
            </>
          )}
        </nav>
      </header>
      <main>
        {session.status === 'signedIn' ? (
          // One gate per signed-in user: the location question comes before any page (ADR-0015).
          <LocationGate key={session.user.id}>
            <Outlet />
          </LocationGate>
        ) : (
          <Outlet />
        )}
      </main>
    </>
  );
}
