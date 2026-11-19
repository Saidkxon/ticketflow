import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type SyntheticEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { createEvent, getMyEvent, updateEvent, type EventDetail } from '../api/organizer';
import { ApiError } from '../api/problem';
import { useSessionStore } from '../auth/SessionContext';
import {
  EMPTY_EVENT_FORM,
  errorsFromServer,
  formFromEvent,
  toRequest,
  validateEventForm,
  type EventFormErrors,
  type EventFormValues,
} from '../organizer/eventForm';
import { LayoutSection } from '../organizer/LayoutSection';
import { LayoutSummary } from '../organizer/LayoutSummary';
import { messageForOrganizerError, referenceFor } from '../organizer/messages';
import { PublishPanel } from '../organizer/PublishPanel';

/** Creates a draft event, or edits one of the organizer's own (the address says which). */
export function OrganizerEventFormPage() {
  const { eventId } = useParams();
  const store = useSessionStore();
  // Saving changes the event's version, which restarts the form; the page remembers the save so "Saved." survives that.
  const [savedVersion, setSavedVersion] = useState<number | null>(null);
  const queryClient = useQueryClient();
  const [layoutDirty, setLayoutDirty] = useState(false);
  // Reloading after a change made elsewhere restarts the forms with the fresh data.
  const [reloadCount, setReloadCount] = useState(0);
  const { data, error, isPending, refetch } = useQuery({
    queryKey: ['my-event', eventId],
    queryFn: () => getMyEvent((input, init) => store.authorizedFetch(input, init), eventId ?? ''),
    enabled: eventId !== undefined,
  });

  async function reload() {
    await Promise.all([refetch(), queryClient.invalidateQueries({ queryKey: ['my-layout', eventId] })]);
    setReloadCount((count) => count + 1);
  }

  if (eventId === undefined) {
    return (
      <>
        <h1>New event</h1>
        <EventForm key="new" />
      </>
    );
  }
  if (isPending) {
    return <p role="status">Loading the event…</p>;
  }
  if (error) {
    return (
      <div role="alert">
        <p>{messageForOrganizerError(error)}</p>
        <p>
          <Link to="/organizer">Back to my events</Link>
        </p>
        {!(error instanceof ApiError && error.status === 404) && (
          <button type="button" onClick={() => void refetch()}>
            Try again
          </button>
        )}
      </div>
    );
  }
  return (
    <>
      <h1>Edit event</h1>
      <EventForm
        key={`${data.id}-${reloadCount}`}
        event={data}
        saved={savedVersion === data.version}
        onSaved={setSavedVersion}
        onReload={() => void reload()}
      />
      {data.status === 'PUBLISHED' ? (
        <LayoutSummary eventId={data.id} />
      ) : (
        <>
          <LayoutSection
            key={`layout-${reloadCount}`}
            event={data}
            onDirtyChange={setLayoutDirty}
            onReload={() => void reload()}
          />
          <PublishPanel event={data} layoutDirty={layoutDirty} onReload={() => void reload()} />
        </>
      )}
    </>
  );
}

function EventForm({
  event,
  saved = false,
  onSaved = () => undefined,
  onReload = () => undefined,
}: {
  event?: EventDetail;
  saved?: boolean;
  onSaved?: (version: number | null) => void;
  onReload?: () => void;
}) {
  const store = useSessionStore();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [values, setValues] = useState<EventFormValues>(event ? formFromEvent(event) : EMPTY_EVENT_FORM);
  const [errors, setErrors] = useState<EventFormErrors>({});
  const [failure, setFailure] = useState<unknown>(null);
  const [pending, setPending] = useState(false);
  const published = event?.status === 'PUBLISHED';

  function change(key: keyof EventFormValues, value: string) {
    setValues((current) => ({ ...current, [key]: value }));
    onSaved(null);
  }

  async function submit(submitted: SyntheticEvent<HTMLFormElement>) {
    submitted.preventDefault();
    setFailure(null);
    onSaved(null);
    const problems = validateEventForm(values);
    setErrors(problems);
    if (Object.keys(problems).length > 0) {
      return;
    }
    setPending(true);
    const fetcher = (input: string, init?: RequestInit) => store.authorizedFetch(input, init);
    try {
      const body = toRequest(values);
      if (event) {
        const updated = await updateEvent(fetcher, event.id, event.version, body);
        queryClient.setQueryData(['my-event', event.id], updated);
        void queryClient.invalidateQueries({ queryKey: ['my-events'] });
        onSaved(updated.version);
      } else {
        const created = await createEvent(fetcher, body);
        queryClient.setQueryData(['my-event', created.id], created);
        void queryClient.invalidateQueries({ queryKey: ['my-events'] });
        void navigate(`/organizer/events/${created.id}`);
      }
    } catch (caught) {
      setFailure(caught);
      if (caught instanceof ApiError) {
        setErrors(errorsFromServer(caught.fieldErrors));
      }
    } finally {
      setPending(false);
    }
  }

  function field(key: keyof EventFormValues, label: string, type = 'text', extra: object = {}) {
    const message = errors[key];
    return (
      <div className="field">
        <label>
          {label}
          <input
            type={type}
            name={key}
            value={values[key]}
            disabled={published}
            aria-invalid={message ? true : undefined}
            aria-describedby={message ? `${key}-error` : undefined}
            onChange={(changed) => change(key, changed.target.value)}
            {...extra}
          />
        </label>
        {message && (
          <p className="field-error" id={`${key}-error`}>
            {message}
          </p>
        )}
      </div>
    );
  }

  return (
    <form noValidate onSubmit={(submitted) => void submit(submitted)}>
      {published && <p role="status">This event is published. Its details can no longer be changed.</p>}
      {field('title', 'Title', 'text', { maxLength: 200 })}
      {field('startsAt', 'Starts at', 'datetime-local')}
      {field('venueName', 'Venue name', 'text', { maxLength: 200 })}
      {field('city', 'City', 'text', { maxLength: 100 })}
      <p className="hint">
        The coordinates are optional. With them, visitors can find the event with a &quot;near me&quot; search.
      </p>
      {field('venueLatitude', 'Latitude (optional)', 'text', { inputMode: 'decimal', placeholder: '41.3111' })}
      {field('venueLongitude', 'Longitude (optional)', 'text', { inputMode: 'decimal', placeholder: '69.2797' })}
      {failure !== null && (
        <div role="alert">
          <p>{messageForOrganizerError(failure)}</p>
          {referenceFor(failure) && (
            <p>
              <small>Reference: {referenceFor(failure)}</small>
            </p>
          )}
          {failure instanceof ApiError && failure.code === 'PRECONDITION_FAILED' && event && (
            <button type="button" onClick={onReload}>
              Reload the event
            </button>
          )}
        </div>
      )}
      {saved && <p role="status">Saved.</p>}
      {!published && (
        <button type="submit" disabled={pending}>
          {event ? 'Save changes' : 'Create draft'}
        </button>
      )}
      <p>
        <Link to="/organizer">Back to my events</Link>
      </p>
    </form>
  );
}
