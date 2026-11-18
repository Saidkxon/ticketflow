import { useState } from 'react';
import { Link, Outlet } from 'react-router';
import { useSessionState, useSessionStore } from './auth/SessionContext';

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
        <Outlet />
      </main>
    </>
  );
}
