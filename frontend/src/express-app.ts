import { Request, Response, NextFunction, RequestHandler } from 'express';
import { AppServices } from './composition-root';
import { AppConfig } from './config';
import { createIndexRouter } from './routes/index';
import { createReportExecutionRouter } from './routes/report-execution';
import { createAuthRouter } from './auth/auth-router';
import { createBackchannelLogoutHandler } from './auth/backchannel-logout';
import { SessionIndex } from './auth/session-index';
import { requireAuth } from './auth/require-auth';
import { withBackendHost } from './auth/backend-host';
import { verifyRequestOrigin } from './auth/csrf';
import { redactSensitiveQuery } from './auth/url-redaction';
import * as logger from './telemetry/logger';

const GENERIC_ERROR_MESSAGE = 'Es ist ein Fehler aufgetreten. Bitte versuche es später erneut.';

const HEALTHCHECK_TIMEOUT_MS = 3_000;

// Only a network-level failure (connection refused, DNS failure, timeout) should fail this check -
// any actual HTTP response (even a 404/401) proves the endpoint is reachable, which is all this
// probes for.
async function checkReachable(url: URL): Promise<void> {
  await fetch(url, { signal: AbortSignal.timeout(HEALTHCHECK_TIMEOUT_MS) });
}

var createError = require('http-errors');
var express = require('express');
var path = require('path');
var requestLogger = require('morgan');
var session = require('express-session');
var helmet = require('helmet');

export function createApp(services: AppServices, config: AppConfig, extraMiddleware: RequestHandler[] = []) {
  var app = express();

  // The store is created explicitly rather than left to express-session's default because two
  // things need it: the session middleware below, and the back-channel logout endpoint, which
  // destroys sessions belonging to entirely different requests. MemoryStore is fine for a single
  // instance; swap it - together with SessionIndex, which is in-process for the same reason - for
  // a shared store (e.g. redis) before scaling out.
  const sessionStore = new session.MemoryStore();
  const sessionIndex = new SessionIndex();

  // view engine setup
  app.set('views', path.join(__dirname, 'templates'));
  app.set('view engine', 'pug');

  // Only styleSrc/fontSrc are overridden: helmet's defaults for everything else (default-src,
  // script-src, img-src, ...) already match what this app needs, so restating them here would
  // just be a no-op duplicate of the built-in defaults. Both overrides narrow the default (which
  // allows any https: origin, plus 'unsafe-inline' for styles) now that fonts are self-hosted
  // (see style.css) and no templates use inline styles.
  app.use(
    helmet({
      contentSecurityPolicy: {
        directives: {
          styleSrc: ["'self'"],
          fontSrc: ["'self'"],
        },
      },
    }),
  );

  // morgan's built-in :url would write /auth/callback?code=...&state=... verbatim into the access
  // log (and from there the OTLP export), so it is replaced by a redacting one.
  requestLogger.token('url', (req: Request) => redactSensitiveQuery(req.originalUrl || req.url));

  // Routed through the OTel logger (not straight to stdout) so access lines share the same
  // pipeline - and, in production, the same OTLP export - as every other log event instead of
  // silently only reaching a local terminal.
  app.use(
    requestLogger(app.get('env') === 'production' ? 'combined' : 'dev', {
      stream: { write: (message: string) => logger.event('http.access', { message: message.trimEnd() }) },
    }),
  );
  app.use(express.json());
  app.use(express.urlencoded({ extended: false }));
  app.use(express.static(path.join(__dirname, 'public')));

  app.use(function (req: Request, res: Response, next: NextFunction) {
    res.locals.memberManagementUrl = config.groups.hitobitoApiUrl;
    res.locals.supportEmail = config.support.helpEmail;
    next();
  });

  app.get('/healthz', async function (req: Request, res: Response) {
    const checks: ReadonlyArray<[string, Promise<unknown>]> = [
      ['metadata-db', services.metadataService.findFor('__healthz__')],
      ['executor', checkReachable(new URL(config.execution.apiUrl))],
      [
        'oidc-discovery',
        checkReachable(
          withBackendHost(new URL('/.well-known/openid-configuration', config.auth.issuerUrl), config.auth.backendHost),
        ),
      ],
    ];

    const results = await Promise.allSettled(checks.map(([, promise]) => promise));
    const failedChecks = results
      .map((result, index) => ({ name: checks[index][0], result }))
      .filter((entry): entry is { name: string; result: PromiseRejectedResult } => entry.result.status === 'rejected');

    if (failedChecks.length > 0) {
      // Logged server-side only - the response itself stays generic so it doesn't hand an
      // unauthenticated caller internal hostnames/network topology.
      failedChecks.forEach(({ name, result }) => logger.error('healthz.check_failed', { check: name }, result.reason));
      res.status(503).json({ status: 'unhealthy', failedChecks: failedChecks.map(({ name }) => name) });
      return;
    }

    res.status(200).json({ status: 'ok' });
  });

  // Deliberately ahead of both the session middleware and the CSRF origin check: this is a
  // server-to-server POST from the OP - no cookie, no session of its own, no browser behind it -
  // and the signed logout token it carries is its own credential, so an origin check would add
  // nothing here while silently 403-ing the logout if an OP ever did send an Origin header.
  app.post(
    '/auth/backchannel-logout',
    createBackchannelLogoutHandler(services.verifyLogoutToken, sessionStore, sessionIndex),
  );

  // A JWKS endpoint is meant to be fetched anonymously by the AS (it is how Keycloak learns the
  // key that signs this app's client assertions - see auth/client-jwt.ts), so this is public by
  // design, same as /healthz above. Deliberately ahead of the session middleware: the AS sends no
  // cookie and the response must never create a session.
  app.get('/.well-known/jwks.json', function (req: Request, res: Response) {
    res.json(services.clientJwks);
  });

  app.use(
    session({
      store: sessionStore,
      secret: config.auth.sessionSecret,
      resave: false,
      saveUninitialized: false,
      cookie: {
        httpOnly: true,
        sameSite: 'lax',
        secure: config.nodeEnv === 'production',
      },
    }),
  );

  app.use(verifyRequestOrigin(new URL(config.auth.redirectUri).origin));

  extraMiddleware.forEach((middleware) => app.use(middleware));

  const authRouter = createAuthRouter(
    services.authClient,
    config.auth,
    config.groups.hitobitoApiUrl,
    sessionIndex,
    services.dpopHandle,
    services.clientAuthentication,
  );
  app.use('/auth', authRouter);

  app.use('/', requireAuth, createReportExecutionRouter(services, config));
  app.use('/', requireAuth, createIndexRouter(services));

  // catch 404 and forward to error handler
  app.use(function (req: Request, res: Response, next: NextFunction) {
    next(createError(404, 'Pfad nicht gefunden'));
  });

  // error handler
  app.use(function (
    err: { message: string; status?: number; expose?: boolean; stack?: string },
    req: Request,
    res: Response,
    next: NextFunction,
  ) {
    const status = err.status || 500;

    logger.error(
      'http.request.failed',
      { 'http.status_code': status, 'http.method': req.method, 'http.path': redactSensitiveQuery(req.originalUrl) },
      err,
    );

    res.locals.message = err.expose ? err.message : GENERIC_ERROR_MESSAGE;
    res.locals.status = status;

    res.status(status);
    res.render('error');
  });

  return app;
}
