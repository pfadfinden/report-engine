import path from 'path';
import { defineConfig } from '@playwright/test';

require('dotenv').config({ path: path.join(__dirname, 'e2e/test.env') });

const executorPort = process.env.E2E_EXECUTOR_PORT ?? '3101';
const frontendPort = process.env.E2E_FRONTEND_PORT ?? '3100';
const executorApiKey = process.env.E2E_EXECUTOR_API_KEY ?? 'e2e-test-executor-api-key';

const referenceOutputDir = path.join(__dirname, '../test-fixtures/reference-output');
const outputDir = path.join(__dirname, 'e2e/.runtime/output');
const jdbcUrl =
  `jdbc:postgresql://${process.env.PGHOST}:${process.env.PGPORT}/${process.env.PGDATABASE}` +
  `?user=${process.env.PGUSER}&password=${process.env.PGPASSWORD}`;

/**
 * True e2e suite (frontend talking to a real running executor - see ReportExecutionIntegrationTest
 * and PipelineIntegrationTest for the module-to-datasource integration tests this complements).
 * Boots two real processes and drives the frontend through an actual browser:
 *
 * - the frontend itself (e2e/server.ts - the real app, only GroupsService and login are faked;
 *   see that file for why)
 * - local-report-executor, unmodified, pointed at a real Postgres seeded by "pretest:e2e" (see
 *   package.json / e2e/prepare-fixtures.ts) and at the compiled report bundle checked into
 *   ../test-fixtures/reference-output (reports/members/report.jasper, _shared/*) - the same
 *   golden artifacts PipelineIntegrationTest (preprocessor) verifies a real preprocessor run
 *   produces from ../test-fixtures/reports, so nothing needs recompiling here.
 *
 * Needs local-report-executor's JAR already built (`mvn package -pl local-report-executor -am`
 * from the repo root) and a reachable Postgres (see e2e/test.env) before "npm run test:e2e".
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.ts',
  timeout: 60_000,
  fullyParallel: false,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: `http://localhost:${frontendPort}`,
    trace: 'retain-on-failure',
  },
  webServer: [
    {
      command: 'node -r ts-node/register -r tsconfig-paths/register e2e/server.ts',
      port: Number(frontendPort),
      reuseExistingServer: !process.env.CI,
      env: {
        E2E_FRONTEND_PORT: frontendPort,
        E2E_EXECUTOR_URL: `http://localhost:${executorPort}`,
        E2E_EXECUTOR_API_KEY: executorApiKey,
        E2E_SESSION_SECRET: process.env.E2E_SESSION_SECRET ?? 'e2e-test-session-secret',
      },
    },
    {
      command: `java -jar ${path.join(__dirname, '../local-report-executor/target/local-report-executor.jar')}`,
      port: Number(executorPort),
      reuseExistingServer: !process.env.CI,
      env: {
        PORT: executorPort,
        REPORTS_DIR: path.join(referenceOutputDir, 'reports'),
        SHARED_ASSETS_DIR: path.join(referenceOutputDir, '_shared'),
        OUTPUT_DIR: outputDir,
        REPORT_SOURCEDATA_DATABASE_URL: jdbcUrl,
        DOWNLOAD_URL_SIGNING_SECRET: process.env.E2E_DOWNLOAD_SIGNING_SECRET ?? 'e2e-test-signing-secret',
        EXECUTOR_API_KEY: executorApiKey,
        PUBLIC_BASE_URL: `http://localhost:${executorPort}`,
      },
    },
  ],
});
