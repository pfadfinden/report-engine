import { Request, RequestHandler, Response } from 'express';
import { Store } from 'express-session';
import { errors } from 'jose';
import { InvalidLogoutTokenError, VerifyLogoutToken } from './logout-token';
import { SessionIndex } from './session-index';
import * as logger from '../telemetry/logger';

/**
 * OpenID Connect Back-Channel Logout 1.0 endpoint: the OP POSTs a signed logout token here when a
 * session ends somewhere else (the user logged out of another app, an admin killed the session,
 * the SSO session expired) and this app destroys the matching local session(s).
 *
 * There is no browser in this exchange, so there is also no cookie to clear — destroying the
 * server-side session is the whole job. The stale cookie the user's browser still holds then
 * resolves to nothing and requireAuth sends them back through login.
 */
export function createBackchannelLogoutHandler(
  verifyLogoutToken: VerifyLogoutToken,
  sessionStore: Store,
  sessionIndex: SessionIndex,
): RequestHandler {
  return async function (req: Request, res: Response) {
    // "must include Cache-Control: no-store in the response" (Back-Channel Logout 1.0, section 2.8)
    res.setHeader('Cache-Control', 'no-store');

    const logoutToken = (req.body as { logout_token?: unknown } | undefined)?.logout_token;
    if (typeof logoutToken !== 'string') {
      res.status(400).json({ error: 'invalid_request', error_description: 'missing logout_token parameter' });
      return;
    }

    let claims;
    try {
      claims = await verifyLogoutToken(logoutToken);
    } catch (err) {
      // A token we can read and reject is the RP's answer to give — 400, nothing for the OP to
      // retry. Failing to *reach* the issuer's JWKS is not: answering 400 there would quietly
      // leave the session alive with no second chance, so that stays a 503 the OP can retry.
      const rejected = err instanceof InvalidLogoutTokenError || isTokenLevelJoseError(err);
      const status = rejected ? 400 : 503;
      logger.error('auth.backchannel_logout.failed', { 'http.status_code': status }, err);
      res.status(status).json({ error: rejected ? 'invalid_request' : 'temporarily_unavailable' });
      return;
    }

    const results = await Promise.all(
      sessionIndex.find(claims).map(async (sessionId) => ({
        sessionId,
        destroyed: await destroySession(sessionStore, sessionId),
      })),
    );

    // Only sessions that are actually gone leave the index; any that failed stay findable so the
    // OP's retry gets another shot at them.
    results.filter(({ destroyed }) => destroyed).forEach(({ sessionId }) => sessionIndex.unregister(sessionId));

    const failed = results.filter(({ destroyed }) => !destroyed).length;

    logger.event('auth.backchannel_logout', {
      'principal.id': claims.sub ?? '',
      'auth.sessions_destroyed': results.length - failed,
    });

    if (failed > 0) {
      res.status(503).json({ error: 'temporarily_unavailable' });
      return;
    }

    res.status(200).end();
  };
}

function destroySession(sessionStore: Store, sessionId: string): Promise<boolean> {
  return new Promise((resolve) => {
    sessionStore.destroy(sessionId, (err) => {
      if (err) {
        logger.error('auth.backchannel_logout.destroy_failed', {}, err);
      }
      resolve(!err);
    });
  });
}

/**
 * Whether a jose failure is about the token rather than about our ability to check it. Every jose
 * error carries a stable code; the JWKS ones are the retrieval side of that line, and anything
 * that isn't a JOSEError at all (a DNS or connection failure inside the JWKS fetch) is too.
 */
function isTokenLevelJoseError(err: unknown): boolean {
  return err instanceof errors.JOSEError && err.code !== 'ERR_JWKS_TIMEOUT';
}
