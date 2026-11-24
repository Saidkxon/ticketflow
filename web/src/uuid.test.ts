import { afterEach, describe, expect, it, vi } from 'vitest';
import { newUuid } from './uuid';

const V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

afterEach(() => vi.unstubAllGlobals());

describe('newUuid', () => {
  it('makes a version-4 UUID, a different one each time', () => {
    const first = newUuid();
    expect(first).toMatch(V4);
    expect(newUuid()).not.toBe(first);
  });

  it('still works where crypto.randomUUID does not exist (plain http)', () => {
    vi.stubGlobal('crypto', { getRandomValues: (bytes: Uint8Array) => bytes.fill(255) });
    expect(newUuid()).toMatch(V4);
  });
});
