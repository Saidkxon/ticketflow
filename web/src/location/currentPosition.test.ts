import { afterEach, describe, expect, it, vi } from 'vitest';
import { getCurrentPoint, PositionFailure } from './currentPosition';

afterEach(() => {
  vi.unstubAllGlobals();
});

function geolocation(behaviour: (ok: PositionCallback, fail: PositionErrorCallback) => void) {
  const getCurrentPosition = vi.fn<(ok: PositionCallback, fail: PositionErrorCallback, options?: PositionOptions) => void>(
    (ok, fail) => behaviour(ok, fail),
  );
  vi.stubGlobal('navigator', { geolocation: { getCurrentPosition } });
  return getCurrentPosition;
}

describe('getCurrentPoint', () => {
  it('gives the latitude and longitude of one reading', async () => {
    const get = geolocation((ok) => ok({ coords: { latitude: 41.3, longitude: 69.2 } } as GeolocationPosition));
    await expect(getCurrentPoint()).resolves.toEqual({ latitude: 41.3, longitude: 69.2 });
    expect(get.mock.calls[0][2]).toMatchObject({ enableHighAccuracy: false, timeout: 15_000 });
  });

  it.each([
    [1, 'denied'],
    [2, 'unavailable'],
    [3, 'timeout'],
  ])('names a failure with code %i as %s', async (code, reason) => {
    geolocation((_ok, fail) => fail({ code } as GeolocationPositionError));
    await expect(getCurrentPoint()).rejects.toMatchObject({ reason });
    await expect(getCurrentPoint()).rejects.toBeInstanceOf(PositionFailure);
  });

  it('says so when the browser has no location support', async () => {
    vi.stubGlobal('navigator', {});
    await expect(getCurrentPoint()).rejects.toMatchObject({ reason: 'unsupported' });
  });
});
