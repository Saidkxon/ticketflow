import { useState, type SyntheticEvent } from 'react';
import { Link, Navigate, useLocation } from 'react-router';
import { messageForAuthError, referenceOf } from '../auth/messages';
import { returnPathFrom } from '../auth/returnPath';
import { useSessionState, useSessionStore } from '../auth/SessionContext';

export function LoginPage() {
  const session = useSessionState();
  const store = useSessionStore();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);

  if (session.status === 'signedIn') {
    return <Navigate to={returnPathFrom(location.state)} replace />;
  }

  async function submit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      await store.login(email.trim(), password);
    } catch (caught) {
      setError(caught);
    } finally {
      setPending(false);
    }
  }

  return (
    <>
      <h1>Sign in</h1>
      <form onSubmit={(event) => void submit(event)}>
        <label>
          Email
          <input
            type="email"
            name="email"
            autoComplete="email"
            required
            maxLength={254}
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
        </label>
        <label>
          Password
          <input
            type="password"
            name="password"
            autoComplete="current-password"
            required
            maxLength={72}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </label>
        {error !== null && (
          <div role="alert">
            <p>{messageForAuthError(error)}</p>
            {referenceOf(error) && (
              <p>
                <small>Reference: {referenceOf(error)}</small>
              </p>
            )}
          </div>
        )}
        <button type="submit" disabled={pending}>
          Sign in
        </button>
      </form>
      <p>
        New here? <Link to="/register">Create an account</Link>
      </p>
    </>
  );
}
