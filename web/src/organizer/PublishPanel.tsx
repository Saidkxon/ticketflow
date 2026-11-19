import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { publishEvent, type EventDetail } from '../api/organizer';
import { ApiError } from '../api/problem';
import { useSessionStore } from '../auth/SessionContext';
import { messageForOrganizerError, referenceFor } from './messages';
import { useMyLayout } from './useMyLayout';

/** Publishing freezes the layout and prices and makes the event public, so it asks twice (FR-3, ADR-0012). */
export function PublishPanel({
  event,
  layoutDirty,
  onReload,
}: {
  event: EventDetail;
  layoutDirty: boolean;
  onReload: () => void;
}) {
  const store = useSessionStore();
  const queryClient = useQueryClient();
  const layout = useMyLayout(event.id);
  const [confirming, setConfirming] = useState(false);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const seats = layout.data?.totalSeats ?? 0;
  // Read once when the panel opens: the server checks the start time again when publishing.
  const [openedAt] = useState(() => Date.now());
  const startsInFuture = new Date(event.startsAt).getTime() > openedAt;
  const ready = seats > 0 && startsInFuture;

  async function publish() {
    setPending(true);
    setFailure(null);
    try {
      const published = await publishEvent((input, init) => store.authorizedFetch(input, init), event.id, event.version);
      queryClient.setQueryData(['my-event', event.id], published);
      void queryClient.invalidateQueries({ queryKey: ['my-events'] });
    } catch (caught) {
      setFailure(caught);
      setConfirming(false);
    } finally {
      setPending(false);
    }
  }

  return (
    <section aria-labelledby="publish-heading">
      <h2 id="publish-heading">Publish</h2>
      <ul>
        <li>{seats > 0 ? `Seating layout saved (${seats} seats).` : 'Seating layout: save a layout first.'}</li>
        <li>{startsInFuture ? 'The start time is in the future.' : 'The start time has passed: change it first.'}</li>
      </ul>
      {layoutDirty && (
        <p className="hint">You have unsaved layout changes. Only the saved layout is published.</p>
      )}
      {!confirming && (
        <button type="button" onClick={() => setConfirming(true)} disabled={!ready || pending}>
          Publish event
        </button>
      )}
      {confirming && (
        <div role="group" aria-label="Confirm publishing">
          <p>
            Publishing makes the event visible to everyone and freezes its seating layout and prices. This cannot be
            undone.
          </p>
          <button type="button" onClick={() => void publish()} disabled={pending}>
            Yes, publish
          </button>{' '}
          <button type="button" onClick={() => setConfirming(false)} disabled={pending}>
            Cancel
          </button>
        </div>
      )}
      {failure !== null && (
        <div role="alert">
          <p>{messageForOrganizerError(failure)}</p>
          {referenceFor(failure) && (
            <p>
              <small>Reference: {referenceFor(failure)}</small>
            </p>
          )}
          {failure instanceof ApiError &&
            (failure.code === 'PRECONDITION_FAILED' || failure.code === 'EVENT_ALREADY_PUBLISHED') && (
              <button type="button" onClick={onReload}>
                Reload the event
              </button>
            )}
        </div>
      )}
    </section>
  );
}
