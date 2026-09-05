import crypto from 'crypto';
import { NextFunction, Request, RequestHandler, Response } from 'express';

const TOKEN_FIELD = '_csrf';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

/**
 * Synchronizer-token CSRF defense: a per-session token is generated once, exposed to templates as
 * `csrfToken` so a form can embed it as a hidden field, and required back on every state-changing
 * request. `sameSite: 'lax'` cookies (see express-app.ts) already block most cross-site POSTs, but
 * this is the belt-and-suspenders check for the cases lax doesn't cover (older/misconfigured
 * clients, a same-site subdomain, a top-level navigation crafted as a POST).
 */
export function ensureCsrfToken(req: Request, res: Response, next: NextFunction): void {
  if (!req.session.csrfToken) {
    req.session.csrfToken = crypto.randomBytes(32).toString('hex');
  }
  res.locals.csrfToken = req.session.csrfToken;
  next();
}

export function requireCsrfToken(): RequestHandler {
  return function (req: Request, res: Response, next: NextFunction) {
    if (SAFE_METHODS.has(req.method)) {
      next();
      return;
    }

    const expected = req.session.csrfToken;
    const presented = (req.body as Record<string, unknown> | undefined)?.[TOKEN_FIELD] ?? req.header('x-csrf-token');

    if (!expected || typeof presented !== 'string' || !constantTimeEquals(presented, expected)) {
      res.status(403).send('Invalid or missing CSRF token.');
      return;
    }

    next();
  };
}

function constantTimeEquals(a: string, b: string): boolean {
  const aBytes = Buffer.from(a);
  const bBytes = Buffer.from(b);
  if (aBytes.length !== bBytes.length) {
    return false;
  }
  return crypto.timingSafeEqual(aBytes, bBytes);
}
