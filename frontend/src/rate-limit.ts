import { NextFunction, Request, RequestHandler, Response } from 'express';
import { ipKeyGenerator, rateLimit } from 'express-rate-limit';
import { AppConfig } from './config';
import * as logger from './telemetry/logger';

var createError = require('http-errors');

const TOO_MANY_REQUESTS_MESSAGE = 'Zu viele Anfragen. Bitte warte einen Moment und versuche es dann erneut.';

/** What the error page needs to offer "try again": re-POST these fields to this path. */
export interface RetryInfo {
  readonly action: string;
  readonly fields: ReadonlyArray<{ name: string; value: string }>;
  readonly retryAfterSeconds: number;
}

// The submitted form is replayed as hidden inputs, so only plain string values are carried over.
function retryInfoFor(req: Request): RetryInfo {
  const resetTime = (req as Request & { rateLimit?: { resetTime?: Date } }).rateLimit?.resetTime;
  const fields = Object.entries(req.body ?? {}).flatMap(([name, value]) =>
    (Array.isArray(value) ? value : [value])
      .filter((entry): entry is string => typeof entry === 'string')
      .map((entry) => ({ name, value: entry })),
  );
  return {
    action: req.path,
    fields,
    retryAfterSeconds: resetTime ? Math.max(1, Math.ceil((resetTime.getTime() - Date.now()) / 1000)) : 60,
  };
}

function limiter(
  name: string,
  windowMs: number,
  limit: number,
  key: (req: Request) => string,
  retryable = false,
): RequestHandler {
  return rateLimit({
    windowMs,
    limit,
    // Standard `RateLimit-*` + `Retry-After` headers; the legacy `X-RateLimit-*` ones are noise.
    standardHeaders: 'draft-7',
    legacyHeaders: false,
    keyGenerator: key,
    // Routed through the app's error handler so the user gets the normal error page, not a bare body.
    handler: (req: Request, _res: Response, next: NextFunction) => {
      logger.warn('rate_limit.exceeded', { limiter: name, 'http.method': req.method, 'http.route': req.path });
      const error = createError(429, TOO_MANY_REQUESTS_MESSAGE);
      if (retryable) {
        error.retry = retryInfoFor(req);
      }
      next(error);
    },
  });
}

// Behind the reverse proxy `trust proxy` is set, so req.ip is the real client address. An IPv6
// address is collapsed to its /56 by express-rate-limit's ipKeyGenerator, so a client can't dodge
// the limit by rotating addresses inside its own prefix.
const byIp = (req: Request) => ipKeyGenerator(req.ip ?? '');

/** Whole-service limit, per client IP. Mount before the routes it should protect. */
export function createGlobalRateLimiter(config: AppConfig['rateLimit']): RequestHandler {
  return limiter('global', config.windowMs, config.maxRequests, byIp);
}

/**
 * Limit for the POST endpoints that actually start a report execution. Keyed per signed-in user
 * (falling back to IP), so one user can't queue up unbounded work on the executor, while users
 * behind a shared NAT don't eat each other's budget. Must run after requireAuth.
 */
export function createExecutionRateLimiter(config: AppConfig['rateLimit']): RequestHandler {
  return limiter(
    'execution',
    config.executionWindowMs,
    config.executionMaxRequests,
    (req) => (req.principal ? `principal:${req.principal.id}` : byIp(req)),
    true,
  );
}
