import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Booking } from '../api/booking';
import { HoldPanel } from './HoldPanel';
import { monotonicNow, type ActiveHold } from './holdClock';

const booking = {
  id: 'b1',
  eventId: 'e1',
  status: 'HELD',
  currency: 'UZS',
  totalMinor: 20000000,
  expiresAt: '2026-11-24T10:10:00Z',
  serverTime: '2026-11-24T10:00:00Z',
  seats: [{ seatId: 's1', section: 'Stalls', row: 'A', number: 1, tier: 'Standard', priceMinor: 20000000 }],
} as Booking;

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

function holdEndingIn(ms: number): ActiveHold {
  return { booking, deadline: monotonicNow() + ms };
}

describe('HoldPanel', () => {
  it('counts down, warns in the last minute and reports the end once', () => {
    const onExpired = vi.fn();
    render(<HoldPanel hold={holdEndingIn(62_000)} busy={false} onRelease={() => undefined} onExpired={onExpired} />);
    expect(screen.getByTestId('countdown')).toHaveTextContent('1:02');
    expect(screen.queryByText('Less than a minute left.')).not.toBeInTheDocument();

    act(() => void vi.advanceTimersByTime(3_000));
    expect(screen.getByTestId('countdown')).toHaveTextContent('0:59');
    expect(screen.getByText('Less than a minute left.')).toBeInTheDocument();
    expect(onExpired).not.toHaveBeenCalled();

    act(() => void vi.advanceTimersByTime(60_000));
    expect(screen.getByTestId('countdown')).toHaveTextContent('0:00');
    expect(onExpired).toHaveBeenCalledTimes(1);
    act(() => void vi.advanceTimersByTime(10_000));
    expect(onExpired).toHaveBeenCalledTimes(1);
  });

  it('is right at once when the tab becomes visible again', () => {
    render(<HoldPanel hold={holdEndingIn(120_000)} busy={false} onRelease={() => undefined} onExpired={() => undefined} />);
    // The timers of a hidden tab do not run, but time passes: the clock is read, not counted.
    vi.setSystemTime(Date.now() + 90_000);
    vi.spyOn(performance, 'now').mockReturnValue(monotonicNow() + 90_000);
    act(() => void document.dispatchEvent(new Event('visibilitychange')));
    expect(screen.getByTestId('countdown')).toHaveTextContent('0:30');
  });

  it('shows the busy state of the release button', () => {
    render(<HoldPanel hold={holdEndingIn(60_000)} busy onRelease={() => undefined} onExpired={() => undefined} />);
    expect(screen.getByRole('button', { name: 'Releasing…' })).toBeDisabled();
  });
});
