import type { ReactNode } from 'react';
import { Outlet } from 'react-router';
import { RequireAuth } from '../auth/RequireAuth';
import { useSessionState } from '../auth/SessionContext';

export const ADMIN_ROLE = 'ADMIN';

/**
 * Shows the administrator screens to a signed-in administrator only. This is a courtesy, not security: Identity checks
 * the ADMIN role on every request (ADR-0004), so anybody else who reached these screens would read nothing.
 */
function RequireAdmin({ children }: { children: ReactNode }) {
  const session = useSessionState();
  if (session.status === 'signedIn' && !session.user.roles.includes(ADMIN_ROLE)) {
    return (
      <>
        <h1>Administration</h1>
        <p>This area needs an administrator account.</p>
      </>
    );
  }
  return <>{children}</>;
}

/** The layout route of /admin: sign-in first, then the role. */
export function AdminArea() {
  return (
    <RequireAuth>
      <RequireAdmin>
        <Outlet />
      </RequireAdmin>
    </RequireAuth>
  );
}
