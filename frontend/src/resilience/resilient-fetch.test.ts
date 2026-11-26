import { resetCircuitBreakers, resilientFetch, UpstreamError, unhealthyUpstreams } from './resilient-fetch';

jest.mock('../telemetry/logger');

function res(status: number, headers: Record<string, string> = {}): Response {
  return new Response(null, { status, headers });
}

function setup(responses: Array<Response | Error>) {
  const queue = [...responses];
  const fetchMock = jest.fn(async () => {
    const next = queue.shift();
    if (next instanceof Error) throw next;
    return next ?? res(200);
  });
  const sleeps: number[] = [];
  let now = 1_000_000;
  const deps = {
    fetch: fetchMock as unknown as typeof fetch,
    sleep: async (ms: number) => {
      sleeps.push(ms);
    },
    random: () => 0.5,
    now: () => now,
  };
  return { fetchMock, sleeps, deps, advance: (ms: number) => (now += ms) };
}

const opts = { timeoutMs: 1000 };

beforeEach(() => resetCircuitBreakers());

describe('resilientFetch', () => {
  it('retries a 503 and returns the eventual success', async () => {
    const { fetchMock, deps } = setup([res(503), res(503), res(200)]);
    const out = await resilientFetch('up', 'http://x', {}, { ...opts, deps });
    expect(out.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('throws UpstreamError(unavailable) once attempts are exhausted', async () => {
    const { fetchMock, deps } = setup([res(502), res(502), res(502)]);
    await expect(resilientFetch('up', 'http://x', {}, { ...opts, deps })).rejects.toMatchObject({
      kind: 'unavailable',
      status: 503,
      upstreamStatus: 502,
    });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('does not retry a 4xx and returns it to the caller', async () => {
    const { fetchMock, deps } = setup([res(404)]);
    const out = await resilientFetch('up', 'http://x', {}, { ...opts, deps });
    expect(out.status).toBe(404);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('does not retry non-idempotent requests', async () => {
    const { fetchMock, deps } = setup([res(503), res(200)]);
    await expect(resilientFetch('up', 'http://x', { method: 'POST' }, { ...opts, deps })).rejects.toBeInstanceOf(
      UpstreamError,
    );
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('honours Retry-After', async () => {
    const { sleeps, deps } = setup([res(429, { 'retry-after': '2' }), res(200)]);
    await resilientFetch('up', 'http://x', {}, { ...opts, deps });
    expect(sleeps).toEqual([2000]);
  });

  it('surfaces a Retry-After longer than the max wait instead of blocking', async () => {
    const { fetchMock, deps } = setup([res(503, { 'retry-after': '120' }), res(200)]);
    await expect(resilientFetch('up', 'http://x', {}, { ...opts, deps })).rejects.toMatchObject({
      retryAfterSec: 120,
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('applies jittered exponential backoff', async () => {
    const { sleeps, deps } = setup([res(503), res(503), res(200)]);
    await resilientFetch('up', 'http://x', {}, { ...opts, baseDelayMs: 100, deps });
    expect(sleeps).toEqual([50, 100]); // random()=0.5 * 100, 0.5 * 200
  });

  it('classifies a timeout as 504', async () => {
    const timeout = Object.assign(new Error('timed out'), { name: 'TimeoutError' });
    const { deps } = setup([timeout, timeout, timeout]);
    await expect(resilientFetch('up', 'http://x', {}, { ...opts, deps })).rejects.toMatchObject({
      kind: 'timeout',
      status: 504,
    });
  });

  it('rethrows caller cancellation without retrying', async () => {
    const controller = new AbortController();
    controller.abort();
    const { fetchMock, deps } = setup([new Error('aborted')]);
    await expect(resilientFetch('up', 'http://x', {}, { ...opts, signal: controller.signal, deps })).rejects.toThrow(
      'aborted',
    );
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  describe('circuit breaker', () => {
    async function trip(deps: ReturnType<typeof setup>['deps']) {
      for (let i = 0; i < 5; i++) {
        await resilientFetch('up', 'http://x', { method: 'POST' }, { ...opts, deps }).catch(() => undefined);
      }
    }

    it('opens after repeated failures and fails fast', async () => {
      const s = setup(Array.from({ length: 5 }, () => res(503)));
      await trip(s.deps);
      expect(unhealthyUpstreams(s.deps.now())).toEqual(['up']);
      s.fetchMock.mockClear();
      await expect(resilientFetch('up', 'http://x', {}, { ...opts, deps: s.deps })).rejects.toMatchObject({
        kind: 'circuit-open',
      });
      expect(s.fetchMock).not.toHaveBeenCalled();
    });

    it('lets one probe through after the cooldown and closes on success', async () => {
      const s = setup([...Array.from({ length: 5 }, () => res(503)), res(200)]);
      await trip(s.deps);
      s.advance(31_000);
      const out = await resilientFetch('up', 'http://x', {}, { ...opts, deps: s.deps });
      expect(out.status).toBe(200);
      expect(unhealthyUpstreams(s.deps.now())).toEqual([]);
    });

    it('re-opens when the probe fails', async () => {
      const s = setup([...Array.from({ length: 5 }, () => res(503)), res(503)]);
      await trip(s.deps);
      s.advance(31_000);
      await resilientFetch('up', 'http://x', { method: 'POST' }, { ...opts, deps: s.deps }).catch(() => undefined);
      await expect(resilientFetch('up', 'http://x', {}, { ...opts, deps: s.deps })).rejects.toMatchObject({
        kind: 'circuit-open',
      });
    });

    it('does not count 4xx or 429 as failures', async () => {
      const s = setup(Array.from({ length: 6 }, (_, i) => res(i % 2 ? 429 : 404)));
      for (let i = 0; i < 6; i++) {
        await resilientFetch('up', 'http://x', { method: 'POST' }, { ...opts, deps: s.deps }).catch(() => undefined);
      }
      expect(unhealthyUpstreams(s.deps.now())).toEqual([]);
    });
  });
});
