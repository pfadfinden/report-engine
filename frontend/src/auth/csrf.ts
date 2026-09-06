import { NextFunction, Request, RequestHandler, Response } from 'express';
import * as logger from '../telemetry/logger';

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

/**
 * Origin/Referer verification CSRF defense (OWASP CSRF Prevention Cheat Sheet, "Verifying
 * Origin with Standard Headers").
 */
export function verifyRequestOrigin(targetOrigin: string): RequestHandler {
  return function (req: Request, res: Response, next: NextFunction) {
    if (SAFE_METHODS.has(req.method)) {
      next();
      return;
    }

    const sourceOrigin = originFromHeader(req.header('origin')) ?? originFromHeader(req.header('referer'));

    if (sourceOrigin !== undefined && sourceOrigin !== targetOrigin) {
      logger.warn('csrf.origin_check_failed', {
        'request.method': req.method,
        'request.path': req.path,
        'header.origin': req.header('origin') ?? '',
        'header.referer': req.header('referer') ?? '',
        'origin.target': targetOrigin,
      });
      res.status(403).send('Invalid request origin.');
      return;
    }

    next();
  };
}

function originFromHeader(value: string | undefined): string | undefined {
  if (!value || value === 'null') {
    return undefined;
  }
  try {
    return new URL(value).origin;
  } catch {
    return undefined;
  }
}
