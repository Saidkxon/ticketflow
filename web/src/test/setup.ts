import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Testing Library only cleans up by itself when test globals are on; we keep them off, so do it here.
afterEach(() => {
  cleanup();
  // A test must not see the page state (for example a chosen seat) that an earlier test kept in the tab.
  window.sessionStorage.clear();
});

// jsdom has no geolocation. By default a test gets a browser that allows location and never reports a movement, so a
// signed-in user passes the location gate and nothing is sent. A test that needs another behavior replaces these.
Object.defineProperty(navigator, 'geolocation', {
  configurable: true,
  value: {
    getCurrentPosition: (success: PositionCallback) =>
      success({
        coords: { latitude: 41.3, longitude: 69.2, accuracy: 10 },
        timestamp: 1_000_000,
      } as GeolocationPosition),
    watchPosition: () => 1,
    clearWatch: () => undefined,
  },
});
