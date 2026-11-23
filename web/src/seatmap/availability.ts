import { fetchAvailability, type AvailabilityResult } from '../api/availability';
import { ApiError } from '../api/problem';

/** What the seat map knows about availability right now. */
export interface AvailabilitySnapshot {
  /** The seat IDs nobody can take at the moment. Empty until the first answer. */
  readonly unavailable: ReadonlySet<string>;
  /** True after the first answer arrived. Before that the map must not pretend that every seat is free. */
  readonly loaded: boolean;
  /** True when the last few attempts failed, so what the map shows may be out of date. */
  readonly stale: boolean;
  /** The error code of the last failed attempt (for example EVENT_NOT_ON_SALE), or null when the last attempt worked. */
  readonly errorCode: string | null;
}

/**
 * Where the seat map gets availability from (ADR-0011): one interface, so a push implementation can replace polling
 * without touching the map. It works with React's useSyncExternalStore: the first subscriber starts it, the last one
 * to leave stops it.
 */
export interface SeatAvailabilitySource {
  subscribe(listener: () => void): () => void;
  getSnapshot(): AvailabilitySnapshot;
  /** Ask again now, for example after a hold was refused because the map was out of date. */
  refresh(): void;
}

export interface PollingOptions {
  /** Time between two requests; the default is 3 seconds (ADR-0011). */
  intervalMs?: number;
  /** Each wait is changed by a random amount of up to this fraction, so many browsers do not fire together. */
  jitter?: number;
  /** Longest wait after failures. */
  maxBackoffMs?: number;
  /** How many failures in a row make the map "stale". */
  staleAfterFailures?: number;
  fetcher?: (eventId: string, etag: string | null, signal: AbortSignal) => Promise<AvailabilityResult>;
  random?: () => number;
  document?: Pick<Document, 'hidden' | 'addEventListener' | 'removeEventListener'>;
}

const EMPTY: ReadonlySet<string> = new Set();

/**
 * Polls the unavailable seats of one event (ADR-0011): every 3 seconds with some jitter, with the ETag of the last list
 * so an unchanged list is a 304. It pauses while the tab is hidden and asks again at once when the tab comes back. On
 * 429 it waits as long as Retry-After says, on other failures it backs off. Leaving the page cancels the request that
 * is still running.
 */
export class PollingSeatAvailability implements SeatAvailabilitySource {
  private snapshot: AvailabilitySnapshot = { unavailable: EMPTY, loaded: false, stale: false, errorCode: null };
  private readonly listeners = new Set<() => void>();
  private etag: string | null = null;
  private failures = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private controller: AbortController | undefined;
  private running = false;

  private readonly intervalMs: number;
  private readonly jitter: number;
  private readonly maxBackoffMs: number;
  private readonly staleAfterFailures: number;
  private readonly fetcher: NonNullable<PollingOptions['fetcher']>;
  private readonly random: () => number;
  private readonly doc: NonNullable<PollingOptions['document']>;

  constructor(
    private readonly eventId: string,
    options: PollingOptions = {},
  ) {
    this.intervalMs = options.intervalMs ?? 3000;
    this.jitter = options.jitter ?? 0.2;
    this.maxBackoffMs = options.maxBackoffMs ?? 30_000;
    this.staleAfterFailures = options.staleAfterFailures ?? 2;
    this.fetcher = options.fetcher ?? ((id, etag, signal) => fetchAvailability(id, etag, signal));
    this.random = options.random ?? Math.random;
    this.doc = options.document ?? document;
  }

  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    if (this.listeners.size === 1) {
      this.start();
    }
    return () => {
      this.listeners.delete(listener);
      if (this.listeners.size === 0) {
        this.stop();
      }
    };
  };

  readonly getSnapshot = (): AvailabilitySnapshot => this.snapshot;

  readonly refresh = (): void => {
    if (this.running && !this.doc.hidden) {
      this.clearTimer();
      void this.poll();
    }
  };

  private start(): void {
    this.running = true;
    this.doc.addEventListener('visibilitychange', this.onVisibility);
    if (!this.doc.hidden) {
      void this.poll();
    }
  }

  private stop(): void {
    this.running = false;
    this.doc.removeEventListener('visibilitychange', this.onVisibility);
    this.clearTimer();
    this.controller?.abort();
    this.controller = undefined;
  }

  private readonly onVisibility = (): void => {
    if (!this.running) {
      return;
    }
    if (this.doc.hidden) {
      // Nobody is looking: stop asking and give up the request that is on its way.
      this.clearTimer();
      this.controller?.abort();
      this.controller = undefined;
    } else {
      this.clearTimer();
      void this.poll();
    }
  };

  private clearTimer(): void {
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
  }

  private async poll(): Promise<void> {
    this.controller?.abort();
    const controller = new AbortController();
    this.controller = controller;
    let waitMs: number;
    try {
      const result = await this.fetcher(this.eventId, this.etag, controller.signal);
      if (controller.signal.aborted) {
        return;
      }
      this.failures = 0;
      if (result.kind === 'changed') {
        this.etag = result.etag;
        this.update({ unavailable: new Set(result.unavailableSeatIds), loaded: true, stale: false, errorCode: null });
      } else if (this.snapshot.stale || this.snapshot.errorCode !== null) {
        this.update({ ...this.snapshot, stale: false, errorCode: null });
      }
      waitMs = this.intervalMs;
    } catch (error) {
      if (controller.signal.aborted) {
        return;
      }
      this.failures += 1;
      const errorCode = error instanceof ApiError ? error.code : 'NETWORK';
      const stale = this.failures >= this.staleAfterFailures;
      if (stale !== this.snapshot.stale || errorCode !== this.snapshot.errorCode) {
        this.update({ ...this.snapshot, stale, errorCode });
      }
      waitMs = this.failureWaitMs(error);
    }
    if (this.running && !controller.signal.aborted) {
      this.timer = setTimeout(() => void this.poll(), this.withJitter(waitMs));
    }
  }

  /** 429 and 503 with a Retry-After: as asked. Anything else: double the wait with every failure, up to the limit. */
  private failureWaitMs(error: unknown): number {
    if (error instanceof ApiError && error.retryAfterSeconds !== undefined) {
      return Math.min(Math.max(error.retryAfterSeconds * 1000, this.intervalMs), this.maxBackoffMs);
    }
    return Math.min(this.intervalMs * 2 ** this.failures, this.maxBackoffMs);
  }

  private withJitter(ms: number): number {
    return Math.round(ms * (1 + this.jitter * (2 * this.random() - 1)));
  }

  private update(next: AvailabilitySnapshot): void {
    this.snapshot = next;
    this.listeners.forEach((listener) => listener());
  }
}
