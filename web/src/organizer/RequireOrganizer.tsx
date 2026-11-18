import type { ReactNode } from 'react';
import { Outlet } from 'react-router';
import { RequireAuth } from '../auth/RequireAuth';
import { useSessionState } from '../auth/SessionContext';

export const ORGANIZER_ROLE = 'ORGANIZER';

/**
 * Shows the organizer screens to a signed-in organizer only. This is a courtesy, not security: the server checks the
 * ORGANIZER role on every request (ADR-0004), so a customer who reached these screens could not read or change anything.
 */
function RequireOrganizer({ children }: { children: ReactNode }) {
  const session = useSessionState();
  if (session.status === 'signedIn' && !session.user.roles.includes(ORGANIZER_ROLE)) {
    return (
      <>
        <h1>Organizer area</h1>
        <p>
          This area needs an organizer account. An administrator gives organizer access; ask them if you should have
          it.
        </p>
      </>
    );
  }
  return <>{children}</>;
}

/** The layout route of /organizer: sign-in first, then the role. */
export function OrganizerArea() {
  return (
    <RequireAuth>
      <RequireOrganizer>
        <Outlet />
      </RequireOrganizer>
    </RequireAuth>
  );
}
