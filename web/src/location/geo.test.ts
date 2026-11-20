import { describe, expect, it } from 'vitest';
import { distanceMeters, shouldSend, type Fix } from './geo';

const START: Fix = { latitude: 41.3111, longitude: 69.2797, atMs: 1_000_000 };

function moved(metersNorth: number, afterMs: number): Fix {
  // One degree of latitude is about 111,195 m.
  return { latitude: START.latitude + metersNorth / 111_195, longitude: START.longitude, atMs: START.atMs + afterMs };
}

describe('distanceMeters', () => {
  it('is zero for the same place', () => {
    expect(distanceMeters(START, START)).toBe(0);
  });

  it('measures about 111 km for one degree of latitude', () => {
    const other = { ...START, latitude: START.latitude + 1 };
    expect(distanceMeters(START, other)).toBeGreaterThan(111_000);
    expect(distanceMeters(START, other)).toBeLessThan(111_400);
  });
});

describe('shouldSend', () => {
  it('always sends the first reading', () => {
    expect(shouldSend(null, START)).toBe(true);
  });

  it('does not send when the person has not moved far enough, however long it took', () => {
    expect(shouldSend(START, moved(150, 600_000))).toBe(false);
  });

  it('does not send inside a minute, however far the person moved', () => {
    expect(shouldSend(START, moved(5_000, 59_000))).toBe(false);
  });

  it('sends after 200 m and a minute', () => {
    expect(shouldSend(START, moved(250, 60_000))).toBe(true);
  });
});
