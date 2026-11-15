import { describe, expect, it } from 'vitest';
import { returnPathFrom } from './returnPath';

describe('returnPathFrom', () => {
  it('returns a path inside the app', () => {
    expect(returnPathFrom({ from: '/account' })).toBe('/account');
  });

  it('falls back to the home page for anything else', () => {
    expect(returnPathFrom(null)).toBe('/');
    expect(returnPathFrom(undefined)).toBe('/');
    expect(returnPathFrom({})).toBe('/');
    expect(returnPathFrom({ from: 42 })).toBe('/');
    expect(returnPathFrom({ from: 'https://evil.example/' })).toBe('/');
    expect(returnPathFrom({ from: '//evil.example/' })).toBe('/');
    expect(returnPathFrom({ from: 'account' })).toBe('/');
  });
});
