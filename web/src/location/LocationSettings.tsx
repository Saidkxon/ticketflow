import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { saveConsent, type LocationLevel } from '../api/location';
import { useSessionStore } from '../auth/SessionContext';
import { CONSENT_TEXT_VERSION, FULL_DAYS, MINIMUM_DAYS } from './consentText';
import { messageForConsentError } from './messages';
import { consentQueryKey, useConsent } from './useConsent';

/** The account page section that switches between Full and Minimum. There is no "off": the question is mandatory (ADR-0015). */
export function LocationSettings() {
  const { userId, query } = useConsent();
  const store = useSessionStore();
  const queryClient = useQueryClient();
  const [choice, setChoice] = useState<LocationLevel | null>(null);
  const [pending, setPending] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const stored = query.data?.level === 'FULL' || query.data?.level === 'MINIMUM' ? query.data.level : null;
  const selected = choice ?? stored;

  if (query.isPending) {
    return <p role="status">Loading your location settings…</p>;
  }
  if (query.isError || stored === null) {
    return null; // the gate in front of every page deals with a missing answer
  }

  async function save() {
    if (selected === null || selected === stored) {
      return;
    }
    setPending(true);
    setSaved(false);
    setError(null);
    try {
      const result = await saveConsent(store.authorizedFetch, selected, CONSENT_TEXT_VERSION);
      queryClient.setQueryData(consentQueryKey(userId), result);
      setChoice(null);
      setSaved(true);
    } catch (caught) {
      setError(caught);
    } finally {
      setPending(false);
    }
  }

  return (
    <section aria-labelledby="location-settings-title">
      <h2 id="location-settings-title">Location tracking</h2>
      <fieldset>
        <legend>How long your position history is kept</legend>
        <label className="radio">
          <input
            type="radio"
            name="location-level"
            checked={selected === 'FULL'}
            onChange={() => setChoice('FULL')}
          />
          Full: {FULL_DAYS} days
        </label>
        <label className="radio">
          <input
            type="radio"
            name="location-level"
            checked={selected === 'MINIMUM'}
            onChange={() => setChoice('MINIMUM')}
          />
          Minimum: {MINIMUM_DAYS} days
        </label>
      </fieldset>
      {selected === 'MINIMUM' && stored === 'FULL' && (
        <p className="hint">Switching to Minimum deletes your position history older than {MINIMUM_DAYS} days at once.</p>
      )}
      {error !== null && <p role="alert">{messageForConsentError(error)}</p>}
      {saved && <p role="status">Saved.</p>}
      <button type="button" disabled={pending || selected === stored} onClick={() => void save()}>
        Save location setting
      </button>
    </section>
  );
}
