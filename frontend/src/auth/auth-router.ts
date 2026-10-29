import { Request, Response, NextFunction, Router } from 'express';
import {
  authorizationCodeGrant,
  buildAuthorizationUrl,
  buildEndSessionUrl,
  calculatePKCECodeChallenge,
  ClientAuth,
  Configuration,
  DPoPHandle,
  randomNonce,
  randomPKCECodeVerifier,
  randomState,
} from 'openid-client';
import { AppConfig } from '../config';
import { claimsToPrincipal } from './claims-to-principal';
import { fetchHitobitoRoles } from './hitobito-roles';
import { SessionIndex } from './session-index';
import * as logger from '../telemetry/logger';

var express = require('express');

function safeReturnTo(value: unknown): string {
  // only ever redirect back into this app - reject absolute/protocol-relative URLs to avoid an open redirect
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') ? value : '/';
}

export function createAuthRouter(
  configuration: Configuration,
  config: AppConfig['auth'],
  hitobitoApiUrl: string,
  sessionIndex: SessionIndex,
  dpopHandle: DPoPHandle | undefined,
  clientAuthentication: ClientAuth,
): Router {
  const router = express.Router();

  router.get('/login', async function (req: Request, res: Response) {
    const state = randomState();
    const nonce = randomNonce();
    const codeVerifier = randomPKCECodeVerifier();

    req.session.pendingAuth = {
      state,
      nonce,
      codeVerifier,
      returnTo: safeReturnTo(req.query.returnTo),
    };

    const authorizationUrl = buildAuthorizationUrl(configuration, {
      scope: 'openid profile email',
      redirect_uri: config.redirectUri,
      state,
      nonce,
      code_challenge: await calculatePKCECodeChallenge(codeVerifier),
      code_challenge_method: 'S256',
    });

    res.redirect(authorizationUrl.href);
  });

  router.get('/callback', async function (req: Request, res: Response, next: NextFunction) {
    const pendingAuth = req.session.pendingAuth;

    if (!pendingAuth) {
      res.redirect('/auth/login');
      return;
    }

    // Single use: whatever happens next, this state/nonce/verifier triple must not be accepted
    // again (a replayed or retried callback has to start over at /auth/login).
    req.session.pendingAuth = undefined;

    try {
      // config.redirectUri as the base gives authorizationCodeGrant the exact registered
      // redirect_uri (it derives one from this URL by stripping the query string)
      const currentUrl = new URL(req.originalUrl, config.redirectUri);
      // dpopHandle is this app's one process-wide DPoP key (see auth/dpop.ts) - signing the token
      // request with it is what gets the access token issued sender-constrained to it.
      const tokens = await authorizationCodeGrant(
        configuration,
        currentUrl,
        {
          pkceCodeVerifier: pendingAuth.codeVerifier,
          expectedState: pendingAuth.state,
          expectedNonce: pendingAuth.nonce,
        },
        undefined,
        dpopHandle ? { DPoP: dpopHandle } : undefined,
      );

      const claims = tokens.claims();
      if (!claims) {
        throw new Error('OIDC token response did not include an ID token');
      }
      const roles = await fetchHitobitoRoles(
        configuration.serverMetadata().issuer,
        config.brokerIdpAlias,
        hitobitoApiUrl,
        config.clientId,
        clientAuthentication,
        tokens.access_token,
        config.backendHost,
        // The broker endpoint wants a proof for exactly the tokens that were issued DPoP-bound.
        dpopHandle && tokens.token_type === 'dpop' ? { configuration, handle: dpopHandle } : undefined,
      );

      const principal = claimsToPrincipal(claims, roles);
      const returnTo = pendingAuth.returnTo;

      // regenerate the session on login to prevent session fixation
      req.session.regenerate(function (regenerateErr) {
        if (regenerateErr) {
          next(regenerateErr);
          return;
        }

        req.session.principal = principal;
        req.session.idToken = tokens.id_token;

        req.session.save(function (saveErr) {
          if (saveErr) {
            next(saveErr);
            return;
          }
          // Indexed only once the session is actually in the store, so a back-channel logout
          // arriving right behind this login can't look up a session id that isn't there yet.
          // sid is what ties this session to the OP's own; without it (an issuer that doesn't
          // send one) a logout token can still reach every session of that sub.
          sessionIndex.register(req.sessionID, {
            sub: principal.id,
            sid: typeof claims.sid === 'string' ? claims.sid : undefined,
          });
          logger.event('auth.login', {
            'principal.id': principal.id,
            // "dpop" here means the AS honoured the proof and sender-constrained the token;
            // "bearer" means it issued a plain one despite being asked.
            'auth.token_type': tokens.token_type,
          });
          res.redirect(returnTo);
        });
      });
    } catch (err) {
      next(err);
    }
  });

  router.get('/logout', function (req: Request, res: Response, next: NextFunction) {
    const idToken = req.session.idToken;
    const principal = req.session.principal;
    const sessionId = req.sessionID;

    req.session.destroy(function (err) {
      if (err) {
        next(err);
        return;
      }

      sessionIndex.unregister(sessionId);

      logger.event('auth.logout', principal ? { 'principal.id': principal.id } : {});

      const endSessionEndpoint = configuration.serverMetadata().end_session_endpoint;

      const redirectTo = endSessionEndpoint
        ? buildEndSessionUrl(configuration, {
            ...(idToken ? { id_token_hint: idToken } : {}),
            post_logout_redirect_uri: config.postLogoutRedirectUri,
          }).href
        : config.postLogoutRedirectUri;

      res.redirect(redirectTo);
    });
  });

  return router;
}
