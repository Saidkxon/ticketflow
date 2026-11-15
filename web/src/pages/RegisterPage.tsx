import { useState, type SyntheticEvent } from 'react';
import { Link, Navigate, useLocation } from 'react-router';
import { ApiError } from '../api/problem';
import { messageForAuthError, referenceOf } from '../auth/messages';
import { returnPathFrom } from '../auth/returnPath';
import { useSessionState, useSessionStore } from '../auth/SessionContext';

// The limits of contracts/identity.openapi.yaml (RegisterRequest). The server checks them again.
const PASSWORD_MIN = 10;
const PASSWORD_MAX = 72;

export function RegisterPage() {
  const session = useSessionState();
  const store = useSessionStore();
  const location = useLocation();
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);

  if (session.status === 'signedIn') {
    return <Navigate to={returnPathFrom(location.state)} replace />;
  }

  const fieldError = (name: string) =>
    error instanceof ApiError ? error.fieldErrors.find((entry) => entry.field === name)?.problem : undefined;

  async function submit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      await store.register(email.trim(), password, displayName.trim());
    } catch (caught) {
      setError(caught);
    } finally {
      setPending(false);
    }
  }

  return (
    <>
      <h1>Create an account</h1>
      <form onSubmit={(event) => void submit(event)}>
        <label>
          Name
          <input
            type="text"
            name="displayName"
            autoComplete="name"
            required
            maxLength={80}
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
          />
        </label>
        {fieldError('displayName') && <p className="field-error">Name {fieldError('displayName')}</p>}
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
        {fieldError('email') && <p className="field-error">Email {fieldError('email')}</p>}
        <label>
          Password
          <input
            type="password"
            name="password"
            autoComplete="new-password"
            required
            minLength={PASSWORD_MIN}
            maxLength={PASSWORD_MAX}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </label>
        <p className="hint">
          {PASSWORD_MIN} to {PASSWORD_MAX} characters.
        </p>
        {fieldError('password') && <p className="field-error">Password {fieldError('password')}</p>}
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
          Create account
        </button>
      </form>
      <p>
        Already have an account? <Link to="/login">Sign in</Link>
      </p>
    </>
  );
}
