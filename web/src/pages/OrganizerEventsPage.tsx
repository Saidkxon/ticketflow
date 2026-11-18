import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { listMyEvents } from '../api/organizer';
import { useSessionStore } from '../auth/SessionContext';
import { messageForOrganizerError, referenceFor } from '../organizer/messages';

const PAGE_SIZE = 20;
const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

/** The organizer's own events, drafts and published, with a way to create a new one. */
export function OrganizerEventsPage() {
  const store = useSessionStore();
  const [page, setPage] = useState(0);
  const { data, error, isPending, refetch } = useQuery({
    queryKey: ['my-events', page],
    queryFn: () => listMyEvents((input, init) => store.authorizedFetch(input, init), page, PAGE_SIZE),
  });

  return (
    <>
      <h1>My events</h1>
      <p>
        <Link to="/organizer/events/new">New event</Link>
      </p>
      {isPending && <p role="status">Loading your events…</p>}
      {error && (
        <div role="alert">
          <p>{messageForOrganizerError(error)}</p>
          {referenceFor(error) && (
            <p>
              <small>Reference: {referenceFor(error)}</small>
            </p>
          )}
          <button type="button" onClick={() => void refetch()}>
            Try again
          </button>
        </div>
      )}
      {data && data.items.length === 0 && <p>You have no events yet. Create the first one.</p>}
      {data && data.items.length > 0 && (
        <>
          <table>
            <caption className="visually-hidden">Your events</caption>
            <thead>
              <tr>
                <th scope="col">Title</th>
                <th scope="col">Starts</th>
                <th scope="col">City</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((event) => (
                <tr key={event.id}>
                  <td>
                    <Link to={`/organizer/events/${event.id}`}>{event.title}</Link>
                  </td>
                  <td>{dateFormat.format(new Date(event.startsAt))}</td>
                  <td>{event.city}</td>
                  <td>{event.status === 'PUBLISHED' ? 'Published' : 'Draft'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="pager">
            <button type="button" onClick={() => setPage(page - 1)} disabled={page === 0}>
              Previous
            </button>
            <span>
              Page {page + 1} of {Math.max(1, Math.ceil(data.totalItems / PAGE_SIZE))}
            </span>
            <button type="button" onClick={() => setPage(page + 1)} disabled={(page + 1) * PAGE_SIZE >= data.totalItems}>
              Next
            </button>
          </p>
        </>
      )}
    </>
  );
}
