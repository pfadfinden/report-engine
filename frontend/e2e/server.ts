import path from 'path';
import { createApp } from '../src/express-app';
import { AppConfig } from '../src/config';
import { AppServices } from '../src/composition-root';
import { LocalMetadataLoaderService } from '../src/domain/adapter/local-metadata-loader.service';
import { HttpReportExecutionService } from '../src/domain/adapter/http-report-execution.service';
import { FakeGroupsService } from './fixtures/fake-groups.service';
import { E2E_PRINCIPAL } from './fixtures/fake-principal';

/**
 * Boots the real frontend app (real Express wiring, real routers, real session handling, real
 * SqliteMetadataService reading the shared reports.db fixture, real HttpReportExecutionService
 * talking to a real running local-report-executor) for Playwright to drive through a browser.
 *
 * Only two things are faked, both because this stack intentionally has no Keycloak/Hitobito to
 * talk to: GroupsService (see fixtures/fake-groups.service.ts) and login itself - GET
 * /__e2e-login seeds a session the same way a real OIDC callback would (see
 * auth-router.ts#callback), via the extraMiddleware hook createApp exposes for exactly this.
 * authClient is passed as an inert stub since nothing in this test flow reaches /auth/*.
 */
async function main() {
  const port = Number(process.env.E2E_FRONTEND_PORT ?? 3100);

  const config: AppConfig = {
    nodeEnv: 'test',
    port,
    metadata: {
      source: 'local',
      localPath:
        process.env.E2E_REPORTS_DB_PATH ?? path.resolve(__dirname, '../../test-fixtures/reference-output/reports.db'),
      remoteUrl: undefined,
      authToken: undefined,
      cacheDir: undefined,
      cacheTtlMs: 0,
    },
    groups: {
      // Only ever shown in a template link ("memberManagementUrl") in this flow - never fetched.
      hitobitoApiUrl: 'http://hitobito.invalid',
      hitobitoApiToken: 'unused',
      cacheTtlMs: 0,
    },
    execution: {
      apiUrl: process.env.E2E_EXECUTOR_URL ?? 'http://localhost:3101',
      apiToken: process.env.E2E_EXECUTOR_API_KEY,
      downloadMode: 'proxy',
    },
    auth: {
      issuerUrl: 'http://oidc.invalid',
      backendHost: undefined,
      clientId: 'unused',
      clientSecret: undefined,
      redirectUri: 'http://localhost/auth/callback',
      postLogoutRedirectUri: '/',
      sessionSecret: process.env.E2E_SESSION_SECRET ?? 'e2e-test-session-secret',
      brokerIdpAlias: 'unused',
    },
  };

  const services: AppServices = {
    groupsService: new FakeGroupsService(),
    metadataService: await new LocalMetadataLoaderService().load(config.metadata.localPath),
    reportExecutionService: new HttpReportExecutionService(config.execution.apiUrl, config.execution.apiToken),
    // Never dereferenced: createAuthRouter only touches this inside /login, /callback, /logout
    // handlers, none of which this test flow reaches (see /__e2e-login below instead).
    authClient: {} as AppServices['authClient'],
  };

  const app = createApp(services, config, [
    function (req, res, next) {
      if (req.path === '/__e2e-login') {
        req.session.principal = E2E_PRINCIPAL;
        req.session.save((err) => (err ? next(err) : res.redirect('/')));
        return;
      }
      next();
    },
  ]);

  app.listen(port, () => {
    console.log(`[e2e] frontend test server listening on http://localhost:${port}`);
  });
}

main().catch((err) => {
  console.error('[e2e] failed to start frontend test server:', err);
  process.exit(1);
});
