import { useSyncExternalStore } from 'react';
import type { AvailabilitySnapshot, SeatAvailabilitySource } from './availability';

/** The current availability from a source. Subscribing starts the source; leaving the page stops it. */
export function useSeatAvailability(source: SeatAvailabilitySource): AvailabilitySnapshot {
  return useSyncExternalStore(source.subscribe, source.getSnapshot);
}
