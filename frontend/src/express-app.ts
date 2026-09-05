import { Request, Response, NextFunction, RequestHandler } from 'express';
import { AppServices } from './composition-root';
import { AppConfig } from './config';
import { createIndexRouter } from './routes/index';
import { createReportExecutionRouter } from './routes/report-execution';
import { createAuthRouter } from './auth/auth-router';
import { requireAuth } from './auth/require-auth';
import { withBackendHost } from './auth/backend-host';
import { ensureCsrfToken, requireCsrfToken } from './auth/csrf';
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

  // MemoryStore is fine for a single instance; swap for a shared store (e.g. redis) before scaling out
  app.use(
    session({
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

  app.use(ensureCsrfToken);
  app.use(requireCsrfToken());

  extraMiddleware.forEach((middleware) => app.use(middleware));

  app.use('/auth', createAuthRouter(services.authClient, config.auth, config.groups.hitobitoApiUrl));

  app.use('/', requireAuth, createReportExecutionRouter(services, config));
  app.use('/', requireAuth, createIndexRouter(services));

  // catch 404 and forward to error handler
  app.use(function (req: Request, res: Response, next: NextFunction) {
    next(createError(404, 'Nicht gefunden'));
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
      { 'http.status_code': status, 'http.method': req.method, 'http.path': req.originalUrl },
      err,
    );

    res.locals.message = err.expose ? err.message : GENERIC_ERROR_MESSAGE;
    res.locals.status = status;
    res.locals.title = 'Fehler';

    res.status(status);
    res.render('error');
  });

  return app;
}
