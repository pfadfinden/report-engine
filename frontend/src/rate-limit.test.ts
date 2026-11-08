import { createServer, Server } from 'node:http';
import { AddressInfo } from 'node:net';
import { NextFunction, Request, Response } from 'express';
import { createExecutionRateLimiter, createGlobalRateLimiter } from './rate-limit';

var express = require('express');

const LIMITS = { windowMs: 60_000, maxRequests: 3, executionWindowMs: 60_000, executionMaxRequests: 2 };

let server: Server;
let baseUrl: string;

// Principal is taken from a header so the test can act as different users without a session.
function setUp() {
  const app = express();
  app.set('trust proxy', 1);
  app.use((req: Request, _res: Response, next: NextFunction) => {
    const id = req.header('x-test-user');
    if (id) {
      req.principal = { id } as Request['principal'];
    }
    next();
  });
  app.get('/ok', createGlobalRateLimiter(LIMITS), (_req: Request, res: Response) => res.send('ok'));
  app.post('/start', createExecutionRateLimiter(LIMITS), (_req: Request, res: Response) => res.send('started'));
  app.use((err: { status?: number }, _req: Request, res: Response, _next: NextFunction) => {
    res.status(err.status ?? 500).send('error');
  });
  server = createServer(app).listen(0);
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}

afterEach(() => new Promise((resolve) => server.close(resolve)));
beforeEach(setUp);

const statuses = async (count: number, init: RequestInit & { path: string }) =>
  Promise.all(Array.from({ length: count }, () => fetch(baseUrl + init.path, init).then((r) => r.status)));

test('global limiter rejects with 429 once the per-IP budget is used up', async () => {
  const result = await statuses(5, { path: '/ok' });
  expect(result.filter((s) => s === 200)).toHaveLength(3);
  expect(result.filter((s) => s === 429)).toHaveLength(2);
});

test('global limiter keys on the forwarded client IP, not the proxy', async () => {
  await statuses(3, { path: '/ok', headers: { 'x-forwarded-for': '198.51.100.1' } });
  const other = await fetch(baseUrl + '/ok', { headers: { 'x-forwarded-for': '198.51.100.2' } });
  expect(other.status).toBe(200);
});

test('execution limiter budgets each user separately', async () => {
  const alice = await statuses(3, { path: '/start', method: 'POST', headers: { 'x-test-user': 'alice' } });
  expect(alice.sort()).toEqual([200, 200, 429]);

  const bob = await fetch(baseUrl + '/start', { method: 'POST', headers: { 'x-test-user': 'bob' } });
  expect(bob.status).toBe(200);
});

test('429 responses carry a Retry-After hint', async () => {
  await statuses(3, { path: '/ok' });
  const limited = await fetch(baseUrl + '/ok');
  expect(limited.status).toBe(429);
  expect(limited.headers.get('retry-after')).not.toBeNull();
});

test('a rate-limited report start carries the submitted form so it can be retried', async () => {
  let retry: unknown;
  await new Promise((resolve) => server.close(resolve));
  const app = express();
  app.use(express.urlencoded({ extended: false }));
  app.post('/start', createExecutionRateLimiter({ ...LIMITS, executionMaxRequests: 1 }), (_r: Request, res: Response) =>
    res.send('started'),
  );
  app.use((err: { status?: number; retry?: unknown }, _req: Request, res: Response, _next: NextFunction) => {
    retry = err.retry;
    res.status(err.status ?? 500).send('error');
  });
  server = createServer(app).listen(0);
  const url = `http://127.0.0.1:${(server.address() as AddressInfo).port}/start`;
  const post = () =>
    fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: 'reportId=r1&p_x=a&p_x=b',
    });

  expect((await post()).status).toBe(200);
  expect((await post()).status).toBe(429);
  expect(retry).toMatchObject({
    action: '/start',
    fields: [
      { name: 'reportId', value: 'r1' },
      { name: 'p_x', value: 'a' },
      { name: 'p_x', value: 'b' },
    ],
  });
});
