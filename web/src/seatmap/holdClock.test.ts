import { describe, expect, it } from 'vitest';
import type { Booking } from '../api/booking';
import { anchorHold, formatCountdown, remainingMs } from './holdClock';

const booking = (expiresAt: string, serverTime: string) => ({ expiresAt, serverTime }) as Booking;

describe('hold clock', () => {
  it('counts from the difference of the server times, not from the time of this device', () => {
    // The device clock is not used at all: only the two server times and the monotonic moment of sending.
    const hold = anchorHold(booking('2026-11-24T10:10:00Z', '2026-11-24T10:00:00Z'), 5_000);
    expect(hold.deadline).toBe(5_000 + 600_000);
    expect(remainingMs(hold, 5_000)).toBe(600_000);
    expect(remainingMs(hold, 65_000)).toBe(540_000);
  });

  it('never goes below zero', () => {
    const hold = anchorHold(booking('2026-11-24T10:10:00Z', '2026-11-24T10:00:00Z'), 0);
    expect(remainingMs(hold, 9_999_999)).toBe(0);
  });

  it('treats an unreadable time as an ended hold', () => {
    const hold = anchorHold(booking('not a date', '2026-11-24T10:00:00Z'), 1_000);
    expect(remainingMs(hold, 1_000)).toBe(0);
  });

  it('formats minutes and seconds, rounding up so 0:00 means ended', () => {
    expect(formatCountdown(600_000)).toBe('10:00');
    expect(formatCountdown(599_001)).toBe('10:00');
    expect(formatCountdown(59_000)).toBe('0:59');
    expect(formatCountdown(61_500)).toBe('1:02');
    expect(formatCountdown(1)).toBe('0:01');
    expect(formatCountdown(0)).toBe('0:00');
    expect(formatCountdown(-5)).toBe('0:00');
  });
});
