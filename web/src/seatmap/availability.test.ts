import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AvailabilityResult } from '../api/availability';
import { ApiError } from '../api/problem';
import { PollingSeatAvailability, type PollingOptions } from './availability';

class FakeDocument extends EventTarget {
  hidden = false;
  show(): void {
    this.hidden = false;
    this.dispatchEvent(new Event('visibilitychange'));
  }
  hide(): void {
    this.hidden = true;
    this.dispatchEvent(new Event('visibilitychange'));
  }
}

type Fetcher = NonNullable<PollingOptions['fetcher']>;

const changed = (ids: string[], etag = 'W/"1"'): AvailabilityResult => ({
  kind: 'changed',
  etag,
  unavailableSeatIds: ids,
});

function setup(fetcher: Fetcher, options: PollingOptions = {}) {
  const doc = new FakeDocument();
  // random() = 0.5 means "no jitter"
  const source = new PollingSeatAvailability('e1', { fetcher, document: doc, random: () => 0.5, ...options });
  return { doc, source };
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('PollingSeatAvailability', () => {
  it('asks at once, then every 3 seconds, and sends the ETag it got', async () => {
    const fetcher = vi.fn<Fetcher>().mockResolvedValueOnce(changed(['a'])).mockResolvedValue({ kind: 'unchanged' });
    const { source } = setup(fetcher);
    source.subscribe(() => {});

    await vi.advanceTimersByTimeAsync(0);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0][1]).toBeNull();

    await vi.advanceTimersByTimeAsync(2999);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls[1][1]).toBe('W/"1"');
  });

  it('publishes the list, tells its listeners and keeps the snapshot object while nothing changes', async () => {
    const fetcher = vi.fn<Fetcher>().mockResolvedValueOnce(changed(['a', 'b'])).mockResolvedValue({ kind: 'unchanged' });
    const { source } = setup(fetcher);
    expect(source.getSnapshot()).toMatchObject({ loaded: false, stale: false });
    const listener = vi.fn();
    source.subscribe(listener);

    await vi.advanceTimersByTimeAsync(0);
    const first = source.getSnapshot();
    expect(first.loaded).toBe(true);
    expect([...first.unavailable]).toEqual(['a', 'b']);
    expect(listener).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(3000);
    expect(source.getSnapshot()).toBe(first);
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('stops when the last subscriber leaves and gives up the request on its way', async () => {
    let signal: AbortSignal | undefined;
    const fetcher = vi.fn<Fetcher>().mockImplementation((_id, _etag, s) => {
      signal = s;
      return new Promise(() => {});
    });
    const { source } = setup(fetcher);
    const unsubscribe = source.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(0);
    expect(signal?.aborted).toBe(false);

    unsubscribe();
    expect(signal?.aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('keeps running while at least one subscriber is left', async () => {
    const fetcher = vi.fn<Fetcher>().mockResolvedValue({ kind: 'unchanged' });
    const { source } = setup(fetcher);
    const first = source.subscribe(() => {});
    const second = source.subscribe(() => {});
    first();
    await vi.advanceTimersByTimeAsync(3000);
    expect(fetcher).toHaveBeenCalledTimes(2);
    second();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('pauses while the tab is hidden and asks at once when it is visible again', async () => {
    const fetcher = vi.fn<Fetcher>().mockResolvedValue({ kind: 'unchanged' });
    const { source, doc } = setup(fetcher);
    source.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(0);
    expect(fetcher).toHaveBeenCalledTimes(1);

    doc.hide();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(fetcher).toHaveBeenCalledTimes(1);

    doc.show();
    await vi.advanceTimersByTimeAsync(0);
    expect(fetcher).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(3000);
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it('does not start while the tab is hidden', async () => {
    const fetcher = vi.fn<Fetcher>().mockResolvedValue({ kind: 'unchanged' });
    const { source, doc } = setup(fetcher);
    doc.hidden = true;
    source.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(10_000);
    expect(fetcher).not.toHaveBeenCalled();
    doc.show();
    await vi.advanceTimersByTimeAsync(0);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('waits longer after each failure, calls the map stale after two, and recovers', async () => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockRejectedValueOnce(new Error('network'))
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValue(changed(['a']));
    const { source } = setup(fetcher);
    source.subscribe(() => {});

    await vi.advanceTimersByTimeAsync(0);
    expect(source.getSnapshot().stale).toBe(false);
    await vi.advanceTimersByTimeAsync(5999);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(source.getSnapshot().stale).toBe(true);

    await vi.advanceTimersByTimeAsync(11_999);
    expect(fetcher).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetcher).toHaveBeenCalledTimes(3);
    expect(source.getSnapshot()).toMatchObject({ stale: false, loaded: true });
  });

  it('reports the error code of the last failure and clears it when an answer comes', async () => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockRejectedValueOnce(new ApiError(404, 'EVENT_NOT_ON_SALE'))
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValue(changed([]));
    const { source } = setup(fetcher);
    source.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(0);
    expect(source.getSnapshot().errorCode).toBe('EVENT_NOT_ON_SALE');
    await vi.advanceTimersByTimeAsync(6000);
    expect(source.getSnapshot().errorCode).toBe('NETWORK');
    await vi.advanceTimersByTimeAsync(12_000);
    expect(source.getSnapshot()).toMatchObject({ errorCode: null, stale: false, loaded: true });
  });

  it('never waits longer than the limit', async () => {
    const fetcher = vi.fn<Fetcher>().mockRejectedValue(new Error('network'));
    const { source } = setup(fetcher, { maxBackoffMs: 10_000 });
    source.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(0); // failure 1: waits 6 s
    await vi.advanceTimersByTimeAsync(6000); // failure 2: would wait 12 s, limit 10 s
    expect(fetcher).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it('waits as long as Retry-After asks after a 429', async () => {
    const fetcher = vi
      .fn<Fetcher>()
      .mockRejectedValueOnce(new ApiError(429, 'RATE_LIMITED', undefined, [], 20))
      .mockResolvedValue({ kind: 'unchanged' });
    const { source } = setup(fetcher);
    source.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(19_999);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('spreads the waits by up to 20 percent', async () => {
    const early = vi.fn<Fetcher>().mockResolvedValue({ kind: 'unchanged' });
    setup(early, { random: () => 0 }).source.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(2399);
    expect(early).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(early).toHaveBeenCalledTimes(2);

    const late = vi.fn<Fetcher>().mockResolvedValue({ kind: 'unchanged' });
    setup(late, { random: () => 1 }).source.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(3599);
    expect(late).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(late).toHaveBeenCalledTimes(2);
  });

  it('refresh asks again at once', async () => {
    const fetcher = vi.fn<Fetcher>().mockResolvedValue({ kind: 'unchanged' });
    const { source } = setup(fetcher);
    source.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(0);
    source.refresh();
    await vi.advanceTimersByTimeAsync(0);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('ignores an answer that arrives after it was stopped', async () => {
    let resolve: (value: AvailabilityResult) => void = () => {};
    const fetcher = vi.fn<Fetcher>().mockImplementation(() => new Promise((r) => (resolve = r)));
    const { source } = setup(fetcher);
    const listener = vi.fn();
    const unsubscribe = source.subscribe(listener);
    await vi.advanceTimersByTimeAsync(0);
    unsubscribe();
    resolve(changed(['a']));
    await vi.advanceTimersByTimeAsync(0);
    expect(listener).not.toHaveBeenCalled();
    expect(source.getSnapshot().loaded).toBe(false);
  });
});
