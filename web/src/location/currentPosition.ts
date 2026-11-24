export type PositionFailureReason = 'unsupported' | 'denied' | 'unavailable' | 'timeout';

export class PositionFailure extends Error {
  constructor(readonly reason: PositionFailureReason) {
    super(`position failed: ${reason}`);
    this.name = 'PositionFailure';
  }
}

export interface Point {
  latitude: number;
  longitude: number;
}

/**
 * One reading of the device position, for one search ("Near me"). Nothing is remembered: the caller keeps the point
 * in memory for as long as the search is shown. The browser asks for permission by itself the first time.
 */
export function getCurrentPoint(): Promise<Point> {
  return new Promise((resolve, reject) => {
    if (typeof navigator === 'undefined' || !('geolocation' in navigator)) {
      reject(new PositionFailure('unsupported'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (position) => resolve({ latitude: position.coords.latitude, longitude: position.coords.longitude }),
      (error) => {
        // 1 = PERMISSION_DENIED, 3 = TIMEOUT, anything else: the position is not available right now
        reject(new PositionFailure(error.code === 1 ? 'denied' : error.code === 3 ? 'timeout' : 'unavailable'));
      },
      // A position up to five minutes old is fine for "events near me"; an answer should come within 15 seconds.
      { enableHighAccuracy: false, maximumAge: 300_000, timeout: 15_000 },
    );
  });
}
