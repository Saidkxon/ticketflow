import type { Booking } from '../api/booking';

/** A hold and the moment it ends on this device's monotonic clock (performance.now), which a changed system time cannot move. */
export interface ActiveHold {
  booking: Booking;
  /** performance.now() value at which the hold ends. */
  deadline: number;
}

export const monotonicNow = (): number => performance.now();

/**
 * Anchors a hold to the device's clock without trusting it (the server sends expiresAt and serverTime from one database
 * clock). The anchor is the moment the request was SENT: the answer was made some time after that, so the device never
 * shows more time than the server will give.
 */
export function anchorHold(booking: Booking, sentAt: number): ActiveHold {
  const remainingMs = Date.parse(booking.expiresAt) - Date.parse(booking.serverTime);
  return { booking, deadline: sentAt + (Number.isFinite(remainingMs) ? remainingMs : 0) };
}

export function remainingMs(hold: ActiveHold, now: number): number {
  return Math.max(0, hold.deadline - now);
}

/** "9:41". Whole seconds, rounded up, so the display reaches 0:00 only when the hold has ended. */
export function formatCountdown(ms: number): string {
  const total = Math.ceil(Math.max(0, ms) / 1000);
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}
