import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { getLatestPositions, getUserHistory } from '../api/adminLocation';
import { messageForAdminError } from '../admin/messages';
import { LocationMap, type MapPoint } from '../admin/maps/LocationMap';
import { useSessionStore } from '../auth/SessionContext';
import { referenceFor } from '../organizer/messages';

const HISTORY_LIMIT = 500;
const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

// Every read of this data is written to an access log on the server, so the data is read on request only (the first
// visit, the Refresh button, and a click on History) and never by a timer or when the window gets focus.
const READ_ON_REQUEST = { staleTime: Infinity, refetchOnWindowFocus: false, refetchOnReconnect: false } as const;

/** Administrators see where tracked users are (ADR-0015, L5 and L9): a map with names, and one user's history. */
export function AdminLocationsPage() {
  const store = useSessionStore();
  const fetcher = (input: string, init?: RequestInit) => store.authorizedFetch(input, init);
  const [selected, setSelected] = useState<{ id: string; name: string } | null>(null);

  const latest = useQuery({
    queryKey: ['admin-location-latest'],
    queryFn: () => getLatestPositions(fetcher),
    ...READ_ON_REQUEST,
  });
  const history = useQuery({
    queryKey: ['admin-location-history', selected?.id],
    queryFn: () => getUserHistory(fetcher, selected!.id, HISTORY_LIMIT),
    enabled: selected !== null,
    ...READ_ON_REQUEST,
  });

  const peoplePoints: MapPoint[] = useMemo(
    () =>
      (latest.data?.items ?? []).map((item) => ({
        id: item.userId,
        position: { lat: item.latitude, lng: item.longitude },
        title: item.displayName,
        details: [
          `${item.level === 'FULL' ? 'Full' : 'Minimum'} tracking`,
          `Seen ${dateFormat.format(new Date(item.recordedAt))}`,
          `Accuracy about ${Math.round(item.accuracyMeters)} m`,
        ],
      })),
    [latest.data],
  );
  const trailPoints: MapPoint[] = useMemo(
    () =>
      (history.data?.items ?? []).map((item, index) => ({
        id: `${index}`,
        position: { lat: item.latitude, lng: item.longitude },
        title: history.data?.displayName ?? '',
        details: [dateFormat.format(new Date(item.recordedAt)), `Accuracy about ${Math.round(item.accuracyMeters)} m`],
      })),
    [history.data],
  );

  const viewingHistory = selected !== null;
  const activeError = viewingHistory ? history.error : latest.error;

  return (
    <>
      <h1>{viewingHistory ? `Location history: ${selected.name}` : 'User locations'}</h1>
      <p className="hint">
        Every time you open this page, refresh it or open a history, the read is written to an access log (who, when, whose
        data). The page does not refresh by itself.
      </p>

      {viewingHistory ? (
        <p>
          <button type="button" onClick={() => setSelected(null)}>
            Back to all users
          </button>{' '}
          <button type="button" onClick={() => void history.refetch()} disabled={history.isFetching}>
            Refresh
          </button>
        </p>
      ) : (
        <p>
          <button type="button" onClick={() => void latest.refetch()} disabled={latest.isFetching}>
            Refresh
          </button>
        </p>
      )}

      {activeError && (
        <div role="alert">
          <p>{messageForAdminError(activeError)}</p>
          {referenceFor(activeError) && (
            <p>
              <small>Reference: {referenceFor(activeError)}</small>
            </p>
          )}
        </div>
      )}

      {!viewingHistory && latest.isPending && <p role="status">Loading positions…</p>}
      {viewingHistory && history.isPending && <p role="status">Loading history…</p>}

      {!viewingHistory && latest.data && (
        <>
          {latest.data.items.length > 0 && <LocationMap points={peoplePoints} />}
          {latest.data.items.length === 0 ? (
            <p>No user has shared a position yet.</p>
          ) : (
            <table>
              <caption className="visually-hidden">Latest position of every tracked user</caption>
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Tracking</th>
                  <th scope="col">Last seen</th>
                  <th scope="col">Accuracy</th>
                  <th scope="col">
                    <span className="visually-hidden">History</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {latest.data.items.map((item) => (
                  <tr key={item.userId}>
                    <td>{item.displayName}</td>
                    <td>{item.level === 'FULL' ? 'Full' : 'Minimum'}</td>
                    <td>{dateFormat.format(new Date(item.recordedAt))}</td>
                    <td>{Math.round(item.accuracyMeters)} m</td>
                    <td>
                      <button
                        type="button"
                        onClick={() => setSelected({ id: item.userId, name: item.displayName })}
                        aria-label={`History of ${item.displayName}`}
                      >
                        History
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}

      {viewingHistory && history.data && (
        <>
          <p>
            {history.data.items.length} {history.data.items.length === 1 ? 'point' : 'points'}
            {history.data.items.length === HISTORY_LIMIT ? ` (the newest ${HISTORY_LIMIT})` : ''}, tracking level{' '}
            {history.data.level === 'FULL' ? 'Full' : 'Minimum'}.
          </p>
          {history.data.items.length > 0 && <LocationMap points={trailPoints} trail />}
          <ol className="history-list">
            {history.data.items.map((item, index) => (
              <li key={index}>
                {dateFormat.format(new Date(item.recordedAt))}, accuracy about {Math.round(item.accuracyMeters)} m
              </li>
            ))}
          </ol>
        </>
      )}
    </>
  );
}
