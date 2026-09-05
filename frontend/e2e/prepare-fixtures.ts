import fs from 'fs';
import path from 'path';
import { Client } from 'pg';

// Runs as its own process (npm's "pretest:e2e" hook, before Playwright even starts), so it needs
// its own env load - it can't rely on playwright.config.ts having already read this.
require('dotenv').config({ path: path.join(__dirname, 'test.env') });

/**
 * Runs before the e2e Playwright suite (wired as npm's "pretest:e2e" hook - see package.json) to
 * seed the one thing the running stack needs that Playwright itself has no business producing: a
 * seeded Postgres database (see ../../test-fixtures/seed.sql). Idempotent, so this can be re-run
 * against a persistent local Postgres as many times as needed.
 *
 * The compiled report bundle local-report-executor serves (report.jasper + _shared/*) doesn't need
 * building here - playwright.config.ts points REPORTS_DIR/SHARED_ASSETS_DIR directly at the
 * checked-in ../../test-fixtures/reference-output, the same golden artifacts PipelineIntegrationTest
 * (preprocessor) validates a real preprocessor run produces from ../../test-fixtures/reports.
 */

const FIXTURES_DIR = path.resolve(__dirname, '../../test-fixtures');

async function seedDatabase() {
  const client = new Client(); // picks up PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD from env
  await client.connect();
  try {
    // DROP first rather than relying on seed.sql alone, so this is safe to re-run against a
    // persistent local Postgres (seed.sql itself is also used by ReportExecutionIntegrationTest,
    // which needs it to stay a plain CREATE+INSERT - the idempotency handling belongs here).
    await client.query('DROP TABLE IF EXISTS members');
    const seedSql = fs.readFileSync(path.join(FIXTURES_DIR, 'seed.sql'), 'utf8');
    await client.query(seedSql);
    console.log('[e2e] seeded Postgres from test-fixtures/seed.sql');
  } finally {
    await client.end();
  }
}

seedDatabase().catch((err) => {
  console.error('[e2e] failed to prepare fixtures:', err);
  process.exit(1);
});
