import { useEffect, useState } from 'react';

export type GeolocationAccess = 'checking' | 'granted' | 'denied' | 'unavailable';

/**
 * Asks the browser for location permission (the browser shows its own question the first time) and reports the answer.
 * "denied" is only the permission being refused. A position that is merely slow or unavailable is not a refusal.
 * "unavailable" means the browser has no location support, or only offers it on a secure (https) address.
 */
export function useGeolocationAccess(enabled: boolean): { state: GeolocationAccess; retry: () => void } {
  const supported = typeof navigator !== 'undefined' && 'geolocation' in navigator;
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{ attempt: number; state: 'granted' | 'denied' } | null>(null);

  useEffect(() => {
    if (!enabled || !supported) {
      return;
    }
    let cancelled = false;
    navigator.geolocation.getCurrentPosition(
      () => {
        if (!cancelled) {
          setResult({ attempt, state: 'granted' });
        }
      },
      (error) => {
        if (!cancelled) {
          // 1 = PERMISSION_DENIED
          setResult({ attempt, state: error.code === 1 ? 'denied' : 'granted' });
        }
      },
      { maximumAge: 60_000, timeout: 20_000 },
    );
    return () => {
      cancelled = true;
    };
  }, [enabled, supported, attempt]);

  const state: GeolocationAccess = !supported ? 'unavailable' : result?.attempt === attempt ? result.state : 'checking';
  return { state, retry: () => setAttempt((value) => value + 1) };
}
