import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { saveConsent, type LocationLevel } from '../api/location';
import { useSessionStore } from '../auth/SessionContext';
import { allowNotNow } from './config';
import { CONSENT_TEXT_VERSION, ConsentText, FULL_DAYS, MINIMUM_DAYS } from './consentText';
import { messageForConsentError } from './messages';
import { consentQueryKey } from './useConsent';

/**
 * The location question. It replaces the whole app until the person chooses Full or Minimum, and there is no "No"
 * (ADR-0015). The "Not now" link exists only when the build setting allows it (config.ts).
 */
export function ConsentScreen({ userId, onSkip }: { userId: string; onSkip: () => void }) {
  const store = useSessionStore();
  const queryClient = useQueryClient();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function choose(level: LocationLevel) {
    setPending(true);
    setError(null);
    try {
      const saved = await saveConsent(store.authorizedFetch, level, CONSENT_TEXT_VERSION);
      queryClient.setQueryData(consentQueryKey(userId), saved);
    } catch (caught) {
      setError(caught);
    } finally {
      setPending(false);
    }
  }

  return (
    <section aria-labelledby="consent-title">
      <h1 id="consent-title">Your location</h1>
      <ConsentText />
      <p>
        <strong>To use TicketFlow you choose one of the two options below.</strong> Your browser will then ask you to allow
        location access; that must be allowed too.
      </p>
      {error !== null && <p role="alert">{messageForConsentError(error)}</p>}
      <div className="choices">
        <button type="button" disabled={pending} onClick={() => void choose('FULL')}>
          Allow full tracking ({FULL_DAYS} days)
        </button>
        <button type="button" disabled={pending} onClick={() => void choose('MINIMUM')}>
          Allow minimum tracking ({MINIMUM_DAYS} days)
        </button>
      </div>
      {allowNotNow() && (
        <p>
          <button type="button" className="link-button" onClick={onSkip}>
            Not now
          </button>
        </p>
      )}
    </section>
  );
}
