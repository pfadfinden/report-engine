import fs from 'fs';
import path from 'path';
import { SqliteMetadataService } from './sqlite-metadata.service';

// Integration test: this module's SqliteMetadataService against its real data source - a SQLite
// file - rather than mocking better-sqlite3. No other module (in particular, no executor) is
// involved, so this isn't an e2e test under the frontend-executor definition.
//
// Reads the real reports.db the preprocessor produces from the shared fixture at
// ../../../../test-fixtures/reports (see report-engine/test-fixtures) - the same file frontend
// points REPORTS_DB_LOCAL_PATH at in production (see docker-compose.yml) - pre-generated and
// checked in as ../../../../test-fixtures/reference-output/reports.db so this suite doesn't need
// a JVM/Maven available to regenerate it. See preprocessor's PipelineIntegrationTest for the
// pipeline that builds it.
const REPORTS_DB_PATH = path.resolve(__dirname, '../../../../test-fixtures/reference-output/reports.db');

// ../../../../test-fixtures sits outside frontend/, which is this project's own Docker build
// context (see frontend/Dockerfile and docker-compose.yml's "frontend" service - both scope to
// just ./frontend, deliberately, so the shipped image doesn't carry test fixtures) - so it's
// never present when this suite runs inside that container, only when run against a full
// monorepo checkout (host machine, CI). Skips rather than fails for the same reason
// ReportExecutionIntegrationTest (local-report-executor) skips without a real Postgres: an
// unmet external dependency, not a real failure.
const fixtureAvailable = fs.existsSync(REPORTS_DB_PATH);
const describeIfFixtureAvailable = fixtureAvailable ? describe : describe.skip;

describeIfFixtureAvailable('SqliteMetadataService against the shared reports.db fixture', () => {
  const sut = new SqliteMetadataService(REPORTS_DB_PATH);

  test('findFor returns the fixture report for an unrestricted group type', async () => {
    const reports = await sut.findFor('Group::AnyType');

    expect(reports).toEqual([
      {
        id: 'members',
        title: 'Members (Sample)',
        description:
          "Minimal single-table sample report used as a shared test fixture across report-engine's modules. Not a real production report.",
        complex: false,
        outputFormats: ['pdf', 'xlsx'],
        onlyForType: [],
        version: '1.0',
      },
    ]);
  });

  test('findFor("*") also returns the fixture report', async () => {
    const reports = await sut.findFor('*');

    expect(reports.map((r) => r.id)).toEqual(['members']);
  });

  test('getParameterFor returns the report-specific date filter parameter', async () => {
    const parameters = await sut.getParameterFor('members');

    expect(parameters).toEqual([
      {
        name: 'p_joined_after',
        label: 'Joined after',
        description: 'Only include members who joined on or after this date',
        type: 'date',
      },
    ]);
  });
});
