import type { EventDetail } from '../api/organizer';
import { LayoutEditor } from './LayoutEditor';
import { messageForOrganizerError } from './messages';
import { useMyLayout } from './useMyLayout';

/** Loads the layout of a draft event and shows the editor. */
export function LayoutSection({
  event,
  onDirtyChange,
  onReload,
}: {
  event: EventDetail;
  onDirtyChange: (dirty: boolean) => void;
  onReload: () => void;
}) {
  const { data, error, isPending, refetch } = useMyLayout(event.id);
  return (
    <section aria-labelledby="layout-heading">
      <h2 id="layout-heading">Seating layout</h2>
      {isPending && <p role="status">Loading the layout…</p>}
      {error && (
        <div role="alert">
          <p>{messageForOrganizerError(error)}</p>
          <button type="button" onClick={() => void refetch()}>
            Try again
          </button>
        </div>
      )}
      {data && <LayoutEditor event={event} layout={data} onDirtyChange={onDirtyChange} onReload={onReload} />}
    </section>
  );
}
