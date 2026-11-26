import * as logger from '../telemetry/logger';

/**
 * fetch() with a per-attempt timeout, bounded retries with jittered exponential backoff and a
 * small per-upstream circuit breaker - so a blip on a remote dependency is absorbed, and a dead
 * one fails fast with a clear, typed error instead of tying up every request for the full timeout.
 */

export type UpstreamErrorKind = 'timeout' | 'unavailable' | 'circuit-open';

export class UpstreamError extends Error {
  /** Read by the app's error handler: 504 for a timeout, 503 otherwise. */
  readonly status: number;
  /** The message is user-safe (German), so the error handler may show it. */
  readonly expose = true;

  constructor(
    readonly kind: UpstreamErrorKind,
    readonly upstream: string,
    readonly upstreamStatus?: number,
    readonly retryAfterSec?: number,
    options?: { cause?: unknown },
  ) {
    super(
      kind === 'timeout'
        ? 'Ein angebundenes System hat nicht rechtzeitig geantwortet. Bitte versuche es gleich noch einmal.'
        : 'Ein angebundenes System ist gerade nicht erreichbar. Bitte versuche es in einem Moment erneut.',
      options,
    );
    this.name = 'UpstreamError';
    this.status = kind === 'timeout' ? 504 : 503;
  }
}

export interface Dependencies {
  readonly fetch: typeof fetch;
  readonly sleep: (ms: number) => Promise<void>;
  /** Uniform in [0, 1). */
  readonly random: () => number;
  readonly now: () => number;
}

const defaultDependencies: Dependencies = {
  fetch: (...args) => fetch(...args),
  sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  random: Math.random,
  now: Date.now,
};

export interface ResilientFetchOptions {
  /** Per-attempt timeout. */
  readonly timeoutMs: number;
  /**
   * Whether the request may safely be sent more than once. Defaults to true for GET/HEAD only;
   * a non-idempotent call (e.g. triggering a report) is attempted exactly once.
   */
  readonly idempotent?: boolean;
  /** Total attempts for an idempotent request (default 3). */
  readonly maxAttempts?: number;
  readonly baseDelayMs?: number;
  readonly maxDelayMs?: number;
  /** Caller cancellation; never retried. */
  readonly signal?: AbortSignal;
  readonly deps?: Partial<Dependencies>;
}

const RETRYABLE_STATUS = new Set([408, 429, 502, 503, 504]);

const BREAKER_FAILURE_THRESHOLD = 5;
const BREAKER_COOLDOWN_MS = 30_000;

type BreakerState = 'closed' | 'open' | 'half-open';

class CircuitBreaker {
  private failures = 0;
  private openedAt = 0;
  private state: BreakerState = 'closed';
  private probing = false;

  constructor(private readonly upstream: string) {}

  current(now: number): BreakerState {
    if (this.state === 'open' && now - this.openedAt >= BREAKER_COOLDOWN_MS) {
      this.state = 'half-open';
    }
    return this.state;
  }

  /** Throws while open; lets exactly one probe through once the cooldown has passed. */
  acquire(now: number): void {
    const state = this.current(now);
    if (state === 'open' || (state === 'half-open' && this.probing)) {
      throw new UpstreamError('circuit-open', this.upstream, undefined, this.remainingSec(now));
    }
    if (state === 'half-open') {
      this.probing = true;
      logger.warn('upstream.circuit.half_open', { upstream: this.upstream });
    }
  }

  success(): void {
    if (this.state !== 'closed') {
      logger.warn('upstream.circuit.closed', { upstream: this.upstream });
    }
    this.failures = 0;
    this.probing = false;
    this.state = 'closed';
  }

  failure(now: number): void {
    this.failures += 1;
    if (this.state === 'half-open' || this.failures >= BREAKER_FAILURE_THRESHOLD) {
      if (this.state === 'closed') {
        logger.warn('upstream.circuit.opened', { upstream: this.upstream, failures: this.failures });
      }
      this.state = 'open';
      this.openedAt = now;
    }
    this.probing = false;
  }

  private remainingSec(now: number): number {
    return Math.max(1, Math.ceil((BREAKER_COOLDOWN_MS - (now - this.openedAt)) / 1000));
  }
}

const breakers = new Map<string, CircuitBreaker>();

function breakerFor(upstream: string): CircuitBreaker {
  let breaker = breakers.get(upstream);
  if (!breaker) {
    breaker = new CircuitBreaker(upstream);
    breakers.set(upstream, breaker);
  }
  return breaker;
}

/** For tests. */
export function resetCircuitBreakers(): void {
  breakers.clear();
}

/** Upstreams whose breaker is currently not closed (for diagnostics/healthchecks). */
export function unhealthyUpstreams(now: number = Date.now()): string[] {
  return [...breakers.entries()].filter(([, b]) => b.current(now) !== 'closed').map(([name]) => name);
}

/** Retry-After as seconds, from either the delta-seconds or the HTTP-date form. */
function parseRetryAfterSec(res: Response, now: number): number | undefined {
  const value = res.headers.get('retry-after');
  if (!value) {
    return undefined;
  }
  const seconds = Number(value);
  if (Number.isFinite(seconds)) {
    return Math.max(0, Math.ceil(seconds));
  }
  const date = Date.parse(value);
  return Number.isNaN(date) ? undefined : Math.max(0, Math.ceil((date - now) / 1000));
}

function isTimeout(err: unknown): boolean {
  return err instanceof Error && (err.name === 'TimeoutError' || err.name === 'AbortError');
}

async function discard(res: Response): Promise<void> {
  try {
    await res.body?.cancel();
  } catch {
    // Nothing to do: the connection is released either way.
  }
}

/**
 * Behaves like fetch(), except:
 * - a response that stays 408/429/502/503/504 after all attempts, a network error or a timeout
 *   throws an UpstreamError (the app's error handler turns it into a 503/504 page);
 * - any other response - including 4xx - is returned for the caller to interpret, never retried.
 */
export async function resilientFetch(
  upstream: string,
  input: string | URL,
  init: RequestInit,
  options: ResilientFetchOptions,
): Promise<Response> {
  const deps: Dependencies = { ...defaultDependencies, ...options.deps };
  const method = (init.method ?? 'GET').toUpperCase();
  const idempotent = options.idempotent ?? (method === 'GET' || method === 'HEAD');
  const maxAttempts = idempotent ? Math.max(1, options.maxAttempts ?? 3) : 1;
  const baseDelayMs = options.baseDelayMs ?? 200;
  const maxDelayMs = options.maxDelayMs ?? 5_000;
  const breaker = breakerFor(upstream);

  let lastError: UpstreamError | undefined;

  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    breaker.acquire(deps.now());

    const timeoutSignal = AbortSignal.timeout(options.timeoutMs);
    const signal = options.signal ? AbortSignal.any([options.signal, timeoutSignal]) : timeoutSignal;

    let retryAfterSec: number | undefined;
    try {
      const res = await deps.fetch(input, { ...init, signal });
      if (!RETRYABLE_STATUS.has(res.status)) {
        if (res.status >= 500) {
          breaker.failure(deps.now());
        } else {
          breaker.success();
        }
        return res;
      }
      retryAfterSec = parseRetryAfterSec(res, deps.now());
      await discard(res);
      // A 429 means the upstream is alive but throttling us - not a reason to trip the breaker.
      if (res.status === 429) {
        breaker.success();
      } else {
        breaker.failure(deps.now());
      }
      lastError = new UpstreamError('unavailable', upstream, res.status, retryAfterSec);
    } catch (err) {
      if (options.signal?.aborted) {
        throw err; // cancelled by the caller, not an upstream problem
      }
      breaker.failure(deps.now());
      lastError = new UpstreamError(isTimeout(err) ? 'timeout' : 'unavailable', upstream, undefined, undefined, {
        cause: err,
      });
    }

    if (attempt === maxAttempts) {
      break;
    }
    // Full jitter; an explicit Retry-After wins, but one longer than we'd ever wait is not worth
    // blocking the user's request for - surface it instead.
    const backoff = deps.random() * Math.min(maxDelayMs, baseDelayMs * 2 ** (attempt - 1));
    const delayMs = retryAfterSec !== undefined ? retryAfterSec * 1000 : backoff;
    if (delayMs > maxDelayMs) {
      break;
    }
    logger.warn('upstream.retry', {
      upstream,
      attempt,
      'delay.ms': Math.round(delayMs),
      'error.kind': lastError.kind,
      'http.status_code': lastError.upstreamStatus ?? -1,
    });
    await deps.sleep(delayMs);
  }

  throw lastError as UpstreamError;
}
