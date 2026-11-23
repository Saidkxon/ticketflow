import { useQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError, fetchEvent, fetchLayout, type PublishedLayout } from '../api/events';
import { loadSelection, saveSelection } from '../seatmap/selectionStorage';
import { PollingSeatAvailability } from '../seatmap/availability';
import { buildGeometry, type SeatShape } from '../seatmap/geometry';
import { MAX_SEATS_PER_HOLD } from '../seatmap/limits';
import { SeatLegend, SeatMap } from '../seatmap/SeatMap';
import { SelectionSummary } from '../seatmap/SelectionSummary';
import { useSeatAvailability } from '../seatmap/useSeatAvailability';

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'full', timeStyle: 'short' });

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
  return 'Something went wrong while loading this event.';
}

function isNotFound(error: Error | null): boolean {
  return error instanceof ApiError && error.status === 404;
}

/** The page of one event: what, when, where, and the seat map (FR-4, FR-18). */
export function EventPage() {
  const { eventId = '' } = useParams();
  // A new event starts with a clean selection and its own availability source.
  return <EventView key={eventId} eventId={eventId} />;
}

function EventView({ eventId }: { eventId: string }) {
  const event = useQuery({ queryKey: ['event', eventId], queryFn: ({ signal }) => fetchEvent(eventId, signal) });
  // The layout of a published event never changes, so it is fetched once and kept.
  const layout = useQuery({
    queryKey: ['layout', eventId],
    queryFn: ({ signal }) => fetchLayout(eventId, signal),
    staleTime: Infinity,
  });

  // An error shows at once, even while the other request is still running.
  const retry = () => {
    void event.refetch();
    void layout.refetch();
  };
  if (event.isError) {
    return <Failure error={event.error} onRetry={retry} />;
  }
  if (layout.isError) {
    return <Failure error={layout.error} onRetry={retry} />;
  }
  if (event.isPending || layout.isPending) {
    return <p role="status">Loading event…</p>;
  }

  return (
    <>
      <p>
        <Link to="/">← All events</Link>
      </p>
      <h1>{event.data.title}</h1>
      <p>
        <time dateTime={event.data.startsAt}>{dateFormat.format(new Date(event.data.startsAt))}</time>
        <br />
        {event.data.venueName}, {event.data.city}
      </p>
      <SeatPicker eventId={eventId} layout={layout.data} />
    </>
  );
}

function Failure({ error, onRetry }: { error: Error; onRetry: () => void }) {
  if (isNotFound(error)) {
    return (
      <>
        <h1>Event not found</h1>
        <p>This event does not exist or is not on sale.</p>
        <p>
          <Link to="/">Back to the events</Link>
        </p>
      </>
    );
  }
  return (
    <div role="alert">
      <p>{messageFor(error)}</p>
      {error instanceof ApiError && error.correlationId && (
        <p>
          <small>Reference: {error.correlationId}</small>
        </p>
      )}
      <button type="button" onClick={onRetry}>
        Try again
      </button>
    </div>
  );
}

function SeatPicker({ eventId, layout }: { eventId: string; layout: PublishedLayout }) {
  const source = useMemo(() => new PollingSeatAvailability(eventId), [eventId]);
  const availability = useSeatAvailability(source);
  const geometry = useMemo(() => buildGeometry(layout), [layout]);
  // The chosen seats and the message about them live together, so every change is one pure update.
  const [choice, setChoice] = useState<{ selected: ReadonlySet<string>; notice: string | null }>(() => ({
    // A refresh keeps the choice (this tab only). Seats that are not in the layout are dropped here; seats that are
    // taken now are dropped, with a message, when the first availability answer arrives (see below).
    selected: new Set(loadSelection(eventId).filter((id) => geometry.seatsById.has(id))),
    notice: null,
  }));
  const { selected, notice } = choice;
  useEffect(() => saveSelection(eventId, [...selected]), [eventId, selected]);

  // A seat that somebody else took while it was chosen leaves the selection, and the person is told.
  // (Adjusting state while rendering is React's way to derive state from a changed input; no effect is needed.)
  const [seenUnavailable, setSeenUnavailable] = useState(availability.unavailable);
  if (seenUnavailable !== availability.unavailable) {
    setSeenUnavailable(availability.unavailable);
    const lost = [...selected].filter((id) => availability.unavailable.has(id));
    if (lost.length > 0) {
      setChoice({
        selected: new Set([...selected].filter((id) => !availability.unavailable.has(id))),
        notice:
          lost.length === 1
            ? `${describeSeat(geometry.seatsById.get(lost[0]))} was just taken by somebody else. It was removed from your seats.`
            : `${lost.length} of your seats were just taken by somebody else. They were removed from your seats.`,
      });
    }
  }

  const toggle = useCallback((seat: SeatShape) => {
    setChoice((current) => {
      if (current.selected.has(seat.id)) {
        const next = new Set(current.selected);
        next.delete(seat.id);
        return { selected: next, notice: null };
      }
      if (current.selected.size >= MAX_SEATS_PER_HOLD) {
        return { selected: current.selected, notice: `You can choose up to ${MAX_SEATS_PER_HOLD} seats at a time.` };
      }
      return { selected: new Set(current.selected).add(seat.id), notice: null };
    });
  }, []);

  // Selected seats in map order, for the summary.
  const chosen = useMemo(
    () => geometry.rows.flatMap((row) => row.seats).filter((seat) => selected.has(seat.id)),
    [geometry, selected],
  );


  return (
    <>
      {!availability.loaded && !availability.stale && <p role="status">Checking which seats are free…</p>}
      {availability.stale && (
        <p role="status" className="seat-warning">
          {availability.errorCode === 'EVENT_NOT_ON_SALE'
            ? 'Seats for this event are not open for booking yet. Trying again…'
            : availability.loaded
              ? 'The seat map may be out of date. Trying again…'
              : 'The free seats could not be loaded. Trying again…'}
        </p>
      )}
      {notice && (
        <p role="alert" className="seat-warning">
          {notice}
        </p>
      )}
      <p className="seat-hint">
        Select seats with the mouse or touch. With the keyboard: Tab to the map, arrow keys to move, Space to choose.
      </p>
      <SeatMap
        layout={layout}
        unavailable={availability.unavailable}
        selected={selected}
        interactive={availability.loaded}
        onToggle={toggle}
      />
      <SeatLegend />
      <SelectionSummary seats={chosen} currency={layout.currency} onRemove={toggle} />
    </>
  );
}

function describeSeat(seat: SeatShape | undefined): string {
  return seat ? `${seat.sectionName}, row ${seat.rowLabel}, seat ${seat.number}` : 'A seat';
}
