import { useEffect, useRef, useState } from 'react';
import { formatMoney } from '../money';
import { formatCountdown, monotonicNow, remainingMs, type ActiveHold } from './holdClock';

interface Props {
  hold: ActiveHold;
  busy: boolean;
  onRelease: () => void;
  onExpired: () => void;
}

/** The seats the customer holds, the time left and the way to give them back (FR-15). */
export function HoldPanel({ hold, busy, onRelease, onExpired }: Props) {
  const [now, setNow] = useState(monotonicNow);
  const expiredFor = useRef<string | null>(null);
  const left = remainingMs(hold, now);

  useEffect(() => {
    const tick = () => setNow(monotonicNow());
    tick();
    // A timer in a hidden tab runs rarely, but the time is read from the clock, never counted: so it is right at once.
    const id = setInterval(tick, 500);
    document.addEventListener('visibilitychange', tick);
    return () => {
      clearInterval(id);
      document.removeEventListener('visibilitychange', tick);
    };
  }, [hold]);

  useEffect(() => {
    if (left <= 0 && expiredFor.current !== hold.booking.id) {
      expiredFor.current = hold.booking.id;
      onExpired();
    }
  }, [left, hold.booking.id, onExpired]);

  const { booking } = hold;
  return (
    <section aria-labelledby="hold-heading" className="selection hold">
      <h2 id="hold-heading">Your seats are held</h2>
      <p>
        Time left: <strong role="timer" data-testid="countdown">{formatCountdown(left)}</strong>
      </p>
      {left <= 60_000 && left > 0 && <p role="status">Less than a minute left.</p>}
      <ul>
        {booking.seats.map((seat) => (
          <li key={seat.seatId}>
            {seat.section}, row {seat.row}, seat {seat.number} — {formatMoney(seat.priceMinor, booking.currency)}
          </li>
        ))}
      </ul>
      <p>
        <strong>
          Total: <span data-testid="total">{formatMoney(booking.totalMinor, booking.currency)}</span>
        </strong>
      </p>
      <p>Payment is not available yet. Your seats stay held until the time runs out or you release them.</p>
      <button type="button" onClick={onRelease} disabled={busy}>
        {busy ? 'Releasing…' : 'Release seats'}
      </button>
    </section>
  );
}
