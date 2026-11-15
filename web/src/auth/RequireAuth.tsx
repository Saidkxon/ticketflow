import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { useSessionState } from './SessionContext';

/** Shows its content to a signed-in user only; everybody else is sent to the login page and comes back after. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const session = useSessionState();
  const location = useLocation();

  if (session.status === 'loading') {
    return <p role="status">Checking your session…</p>;
  }
  if (session.status === 'signedOut') {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}
