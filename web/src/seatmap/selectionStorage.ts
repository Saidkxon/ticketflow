import { MAX_SEATS_PER_HOLD } from './limits';

const KEY_PREFIX = 'ticketflow.selection.';

/** sessionStorage can be missing or throw (private windows, blocked site data). The page works without it. */
function storage(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

/**
 * The seats a person had chosen on this event in this tab, so a refresh does not lose them. It is only a convenience:
 * a choice is not a reservation (that is a hold), so the page checks the seats against the layout and the current
 * availability before it uses them. It lives in sessionStorage: it stays in this tab and goes when the tab closes.
 */
export function loadSelection(eventId: string): string[] {
  try {
    const raw = storage()?.getItem(KEY_PREFIX + eventId);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed.filter((id): id is string => typeof id === 'string').slice(0, MAX_SEATS_PER_HOLD);
  } catch {
    return [];
  }
}

export function saveSelection(eventId: string, seatIds: readonly string[]): void {
  try {
    const store = storage();
    if (seatIds.length === 0) {
      store?.removeItem(KEY_PREFIX + eventId);
    } else {
      store?.setItem(KEY_PREFIX + eventId, JSON.stringify(seatIds));
    }
  } catch {
    // Nothing to do: the choice just is not kept over a refresh.
  }
}
