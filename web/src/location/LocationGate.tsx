import { useCallback, useState, type ReactNode } from 'react';
import { sendPosition, type PositionRequest } from '../api/location';
import { useSessionStore } from '../auth/SessionContext';
import { allowNotNow } from './config';
import { ConsentScreen } from './ConsentScreen';
import { useConsent } from './useConsent';
import { useGeolocationAccess } from './useGeolocationAccess';
import { usePositionSender } from './usePositionSender';

/**
 * Stands between a signed-in user and the app (ADR-0015). Until the person has answered the current consent text with
 * Full or Minimum, only the consent screen is shown. After that, tracking starts, and if the browser refuses location
 * access the app stays blocked behind an explanation. It is mounted once per signed-in user (see Layout), so a
 * "Not now" is forgotten at the next login.
 */
export function LocationGate({ children }: { children: ReactNode }) {
  const { userId, query } = useConsent();
  const [skipped, setSkipped] = useState(false);

  if (skipped) {
    return <>{children}</>;
  }
  if (query.isPending) {
    return <p role="status">Checking your location settings…</p>;
  }
  if (query.isError) {
    return (
      <section>
        <h1>Your location</h1>
        <p role="alert">Your location settings could not be loaded.</p>
        <button type="button" onClick={() => void query.refetch()}>
          Try again
        </button>
        {allowNotNow() && (
          <button type="button" className="link-button" onClick={() => setSkipped(true)}>
            Not now
          </button>
        )}
      </section>
    );
  }
  const consent = query.data;
  const tracking = !consent.needsAnswer && (consent.level === 'FULL' || consent.level === 'MINIMUM');
  if (!tracking) {
    return <ConsentScreen userId={userId} onSkip={() => setSkipped(true)} />;
  }
  return <Tracking onSkip={() => setSkipped(true)}>{children}</Tracking>;
}

function Tracking({ children, onSkip }: { children: ReactNode; onSkip: () => void }) {
  const store = useSessionStore();
  const access = useGeolocationAccess(true);
  const send = useCallback((point: PositionRequest) => sendPosition(store.authorizedFetch, point), [store]);
  usePositionSender(send, access.state === 'granted');

  if (access.state === 'checking') {
    return <p role="status">Waiting for your browser&apos;s location permission…</p>;
  }
  if (access.state === 'denied' || access.state === 'unavailable') {
    return (
      <section aria-labelledby="blocked-title">
        <h1 id="blocked-title">Location access is required</h1>
        {access.state === 'denied' ? (
          <p role="alert">
            Your browser did not allow TicketFlow to use your location. Allow it in your browser&apos;s site settings
            (the icon next to the address), then try again.
          </p>
        ) : (
          <p role="alert">
            This browser cannot give TicketFlow your location. Location needs a browser that supports it and a secure
            (https) address.
          </p>
        )}
        {access.state === 'denied' && (
          <button type="button" onClick={access.retry}>
            Try again
          </button>
        )}
        {allowNotNow() && (
          <button type="button" className="link-button" onClick={onSkip}>
            Not now
          </button>
        )}
      </section>
    );
  }
  return <>{children}</>;
}
