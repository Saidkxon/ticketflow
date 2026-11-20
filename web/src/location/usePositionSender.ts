import { useEffect, useRef } from 'react';
import type { PositionRequest } from '../api/location';
import { shouldSend, type Fix } from './geo';

/**
 * While enabled, watches the browser position and sends a point to Identity when the person has moved about 200 m and
 * a minute has passed since the last one (ADR-0015, L8). The first reading is sent at once. A failed send is not
 * retried by a timer: the next reading decides again. Coordinates are never logged.
 */
export function usePositionSender(send: (point: PositionRequest) => Promise<void>, enabled: boolean): void {
  const sendRef = useRef(send);
  useEffect(() => {
    sendRef.current = send;
  });

  useEffect(() => {
    if (!enabled || typeof navigator === 'undefined' || !('geolocation' in navigator)) {
      return;
    }
    let last: Fix | null = null;
    const watchId = navigator.geolocation.watchPosition(
      (position) => {
        const fix: Fix = {
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          atMs: position.timestamp,
        };
        if (!shouldSend(last, fix)) {
          return;
        }
        const previous = last;
        last = fix;
        sendRef
          .current({
            latitude: fix.latitude,
            longitude: fix.longitude,
            accuracyMeters: position.coords.accuracy,
            recordedAt: new Date(fix.atMs).toISOString(),
          })
          .catch(() => {
            last = previous; // the next reading tries again
          });
      },
      () => {
        // A reading that fails is skipped; permission problems are handled by useGeolocationAccess.
      },
      { enableHighAccuracy: false, maximumAge: 30_000 },
    );
    return () => navigator.geolocation.clearWatch(watchId);
  }, [enabled]);
}
