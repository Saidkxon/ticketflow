import { useQuery } from '@tanstack/react-query';
import { ApiError, fetchEvents } from './api/events';

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

export function EventList() {
  const { data, error, isPending, refetch } = useQuery({
    queryKey: ['events', { page: 0, size: 20 }],
    queryFn: () => fetchEvents(0, 20),
  });

  if (isPending) {
    return <p role="status">Loading events…</p>;
  }

  if (error) {
    return (
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
    );
  }

  if (data.items.length === 0) {
    return <p>No events yet. Check back soon.</p>;
  }

  return (
    <ul aria-label="Events">
      {data.items.map((event) => (
        <li key={event.id}>
          <strong>{event.title}</strong>
          <br />
          <time dateTime={event.startsAt}>{dateFormat.format(new Date(event.startsAt))}</time>
        </li>
      ))}
    </ul>
  );
}
