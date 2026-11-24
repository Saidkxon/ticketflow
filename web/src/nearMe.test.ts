import { describe, expect, it } from 'vitest';
import { describeDistance, roundForSearch } from './nearMe';

describe('nearMe', () => {
  it('rounds a coordinate to two decimals, about one kilometre', () => {
    expect(roundForSearch(41.31116)).toBe(41.31);
    expect(roundForSearch(69.27976)).toBe(69.28);
    expect(roundForSearch(-0.004)).toBe(-0);
    expect(roundForSearch(-33.8688)).toBe(-33.87);
  });

  it('describes a distance without false precision', () => {
    expect(describeDistance(0.4)).toBe('less than 1 km away');
    expect(describeDistance(12.4)).toBe('about 12 km away');
    expect(describeDistance(12.5)).toBe('about 13 km away');
  });
});
