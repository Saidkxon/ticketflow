import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { ApiError, fetchEvents, type NearSearch } from './api/events';
import { getCurrentPoint, PositionFailure, type PositionFailureReason } from './location/currentPosition';
import { DEFAULT_RADIUS_KM, describeDistance, RADIUS_CHOICES_KM, roundForSearch } from './nearMe';

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'full', timeStyle: 'short' });

/** Words for the codes a person can act on. Everything else gets the general message. */
function messageFor(error: Error): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'UPSTREAM_UNAVAILABLE':
      case 'UPSTREAM_TIMEOUT':
        return 'The event service is not answering right now. Please try again in a moment.';
      case 'RATE_LIMITED':
        return 'Too many requests. Please wait a moment and try again.';
    }
  }
  return 'Something went wrong while loading events.';
}

const POSITION_PROBLEMS: Record<PositionFailureReason, string> = {
  denied: 'Location is turned off for this site. Allow it in your browser settings, or show all events.',
  unsupported: 'Your browser does not offer location on this address. You can still browse all events.',
  timeout: 'Your position took too long to find. Please try again.',
  unavailable: 'Your position could not be found right now. Please try again.',
};

export function EventList() {
  // The point of a "Near me" search lives only here, in memory. It is never saved (ADR-0015).
  const [point, setPoint] = useState<{ latitude: number; longitude: number } | null>(null);
  const [radiusKm, setRadiusKm] = useState<number>(DEFAULT_RADIUS_KM);
  const [locating, setLocating] = useState(false);
  const [problem, setProblem] = useState<PositionFailureReason | null>(null);

  const near: NearSearch | undefined = point ? { ...point, radiusKm } : undefined;
  const { data, error, isPending, refetch } = useQuery({
    queryKey: ['events', { page: 0, size: 20, near: near ?? null }],
    queryFn: () => fetchEvents(0, 20, near),
  });

  async function findMe() {
    setLocating(true);
    setProblem(null);
    try {
      const found = await getCurrentPoint();
      setPoint({ latitude: roundForSearch(found.latitude), longitude: roundForSearch(found.longitude) });
    } catch (failure) {
      setProblem(failure instanceof PositionFailure ? failure.reason : 'unavailable');
    } finally {
      setLocating(false);
    }
  }

  return (
    <>
      <div className="near-me">
        {point ? (
          <>
            <label>
              Within{' '}
              <select value={radiusKm} onChange={(event) => setRadiusKm(Number(event.target.value))}>
                {RADIUS_CHOICES_KM.map((choice) => (
                  <option key={choice} value={choice}>
                    {choice} km
                  </option>
                ))}
              </select>{' '}
              of you
            </label>{' '}
            <button type="button" onClick={() => setPoint(null)}>
              Show all events
            </button>
          </>
        ) : (
          <button type="button" onClick={() => void findMe()} disabled={locating}>
            Near me
          </button>
        )}
        {locating && <p role="status">Finding your position…</p>}
        {problem && <p role="alert">{POSITION_PROBLEMS[problem]}</p>}
      </div>
      {isPending ? (
        <p role="status">Loading events…</p>
      ) : error ? (
        <div role="alert">
          <p>{messageFor(error)}</p>
          {error instanceof ApiError && error.correlationId && (
            <p>
              <small>Reference: {error.correlationId}</small>
            </p>
          )}
          <button type="button" onClick={() => void refetch()}>
            Try again
          </button>
        </div>
      ) : data.items.length === 0 ? (
        <p>
          {near
            ? `No events within ${near.radiusKm} km of you. Try a larger distance, or show all events.`
            : 'No events yet. Check back soon.'}
        </p>
      ) : (
        <>
          <ul aria-label="Events">
            {data.items.map((event) => (
              <li key={event.id}>
                <Link to={`/events/${event.id}`}>
                  <strong>{event.title}</strong>
                </Link>
                <br />
                <time dateTime={event.startsAt}>{dateFormat.format(new Date(event.startsAt))}</time>
                {near && event.distanceKm != null && (
                  <>
                    <br />
                    <span>{describeDistance(event.distanceKm)}</span>
                  </>
                )}
              </li>
            ))}
          </ul>
          {near && <p className="near-me-note">Events whose venue location is not known are not shown in this search.</p>}
        </>
      )}
    </>
  );
}
