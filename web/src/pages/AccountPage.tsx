import { useSessionState } from '../auth/SessionContext';

export function AccountPage() {
  const session = useSessionState();
  if (session.status !== 'signedIn') {
    return null; // RequireAuth does not show this page to anybody else
  }
  const { user } = session;
  return (
    <>
      <h1>Your account</h1>
      <dl>
        <dt>Name</dt>
        <dd>{user.displayName}</dd>
        <dt>Email</dt>
        <dd>{user.email}</dd>
        <dt>Roles</dt>
        <dd>{user.roles.join(', ')}</dd>
      </dl>
    </>
  );
}
