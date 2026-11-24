import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useRef, useState } from 'react';
import { findActiveHold, holdSeats, releaseHold, type Fetcher } from '../api/booking';
import { ApiError } from '../api/problem';
import { newUuid } from '../uuid';
import { anchorHold, monotonicNow, type ActiveHold } from './holdClock';

/** What the person is told after an action. An alert interrupts a screen reader, a status waits for its turn. */
export interface HoldMessage {
  kind: 'alert' | 'status';
  text: string;
}

export interface HoldCallbacks {
  /** Seats that the server says are not free: they leave the choice. */
  onSeatsTaken: (seatIds: string[]) => void;
  /** The seats are held now (or already were): the choice is not needed any more. */
  onHeld: () => void;
  /** Ask for the availability again, because the map is out of date or seats were just given back. */
  refreshAvailability: () => void;
}

function messageForError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'SEATS_BUSY':
        return 'Many people are choosing the same seats right now. Please try again in a moment.';
      case 'REQUEST_IN_PROGRESS':
        return 'Your earlier request is still being processed. Please try again in a moment.';
      case 'EVENT_NOT_ON_SALE':
        return 'Seats for this event are not open for booking yet.';
      case 'UNAUTHENTICATED':
        return 'Your session has ended. Please sign in again; your chosen seats are kept.';
      case 'RATE_LIMITED':
        return error.retryAfterSeconds
          ? `Too many requests. Please wait ${error.retryAfterSeconds} seconds and try again.`
          : 'Too many requests. Please wait a moment and try again.';
      case 'INVALID_REQUEST':
        return 'These seats cannot be held. Please check your choice and try again.';
      case 'UPSTREAM_UNAVAILABLE':
      case 'UPSTREAM_TIMEOUT':
        return 'The booking service is not answering right now. Trying again is safe: the same seats are never held twice.';
    }
  }
  return 'Something went wrong. Trying again is safe: the same seats are never held twice.';
}

/**
 * Holding seats, the live hold and releasing it (FR-15, ADR-0012). The hold lives in the query cache under one key, so
 * a hold that was placed, found again after a refresh, or found after a refusal is the same thing to the page.
 *
 * One Idempotency-Key belongs to one choice of seats: a retry of the same choice (after a timeout, say) sends the same
 * key, so the server answers with the booking it already made instead of holding twice. Another choice gets a new key.
 */
export function useHold(
  eventId: string,
  userId: string | null,
  fetcher: Fetcher,
  selected: ReadonlySet<string>,
  callbacks: HoldCallbacks,
) {
  const client = useQueryClient();
  const key = ['activeHold', eventId, userId];
  const lookup = useQuery({
    queryKey: key,
    enabled: userId !== null,
    // The hold is kept up to date by this hook, not by refetching; a new visit to the page always looks again.
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    queryFn: async ({ signal }): Promise<ActiveHold | null> => {
      const sentAt = monotonicNow();
      const booking = await findActiveHold(fetcher, eventId, signal);
      return booking ? anchorHold(booking, sentAt) : null;
    },
  });
  const hold = lookup.data ?? null;

  const [busy, setBusy] = useState<'holding' | 'releasing' | null>(null);
  const [message, setMessage] = useState<HoldMessage | null>(null);
  const working = useRef(false);
  const attempt = useRef<{ signature: string; idempotencyKey: string } | null>(null);

  const setHold = useCallback((next: ActiveHold | null) => client.setQueryData(['activeHold', eventId, userId], next), [
    client,
    eventId,
    userId,
  ]);

  const place = useCallback(async () => {
    if (working.current || selected.size === 0 || userId === null) {
      return;
    }
    working.current = true;
    setBusy('holding');
    setMessage(null);
    const seatIds = [...selected].sort();
    const signature = seatIds.join(',');
    if (attempt.current?.signature !== signature) {
      attempt.current = { signature, idempotencyKey: newUuid() };
    }
    const sentAt = monotonicNow();
    try {
      const booking = await holdSeats(fetcher, eventId, seatIds, attempt.current.idempotencyKey);
      attempt.current = null;
      setHold(anchorHold(booking, sentAt));
      callbacks.onHeld();
    } catch (error) {
      await handleRefusal(error);
    } finally {
      working.current = false;
      setBusy(null);
    }

    async function handleRefusal(error: unknown) {
      if (error instanceof ApiError && error.code === 'SEATS_UNAVAILABLE') {
        attempt.current = null;
        callbacks.onSeatsTaken(error.unavailableSeatIds);
        callbacks.refreshAvailability();
        if (error.unavailableSeatIds.length === 0) {
          setMessage({ kind: 'alert', text: 'Some of these seats are no longer free. Please choose again.' });
        }
        return;
      }
      if (error instanceof ApiError && error.code === 'ACTIVE_HOLD_EXISTS') {
        attempt.current = null;
        const lookedUpAt = monotonicNow();
        try {
          const existing = await findActiveHold(fetcher, eventId);
          if (existing) {
            setHold(anchorHold(existing, lookedUpAt));
            callbacks.onHeld();
            setMessage({ kind: 'status', text: 'You already hold seats for this event. Release them to choose others.' });
            return;
          }
        } catch {
          // Falls through to the plain message below.
        }
        setMessage({ kind: 'alert', text: 'You already hold seats for this event. Reload the page to see them.' });
        return;
      }
      setMessage({ kind: 'alert', text: messageForError(error) });
    }
  }, [callbacks, eventId, fetcher, selected, setHold, userId]);

  const release = useCallback(async () => {
    if (working.current || hold === null) {
      return;
    }
    working.current = true;
    setBusy('releasing');
    setMessage(null);
    try {
      await releaseHold(fetcher, hold.booking.id);
      setHold(null);
      setMessage({ kind: 'status', text: 'Your seats were released.' });
      callbacks.refreshAvailability();
    } catch (error) {
      if (error instanceof ApiError && (error.code === 'BOOKING_NOT_ACTIVE' || error.code === 'NOT_FOUND')) {
        setHold(null);
        setMessage({ kind: 'alert', text: 'This hold had already ended.' });
        callbacks.refreshAvailability();
      } else {
        setMessage({ kind: 'alert', text: 'Your seats could not be released. Please try again.' });
      }
    } finally {
      working.current = false;
      setBusy(null);
    }
  }, [callbacks, fetcher, hold, setHold]);

  /** The countdown reached zero: the server gives the seats back by itself, the page just stops showing the hold. */
  const expire = useCallback(() => {
    setHold(null);
    setMessage({ kind: 'alert', text: 'Your hold has ended and the seats were released. You can choose seats again.' });
    callbacks.refreshAvailability();
  }, [callbacks, setHold]);

  return { hold, checking: lookup.isPending && userId !== null, busy, message, place, release, expire };
}
