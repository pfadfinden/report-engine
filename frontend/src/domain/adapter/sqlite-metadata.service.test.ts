import fs from 'fs';
import os from 'os';
import path from 'path';
import Database from 'better-sqlite3';
import { SqliteMetadataService } from './sqlite-metadata.service';

// Builds a small throwaway reports.db with the same tables/columns the preprocessor's Hibernate
// mapping creates (ParameterMetadata, ParameterOption), rather than relying on the shared
// test-fixtures reports.db: that one is pre-generated and has no select parameter. See
// sqlite-metadata.service.integration.test.ts for the test against the real preprocessor output.
function createDb(withOptionsTable: boolean): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'reports-db-'));
  const dbPath = path.join(dir, 'reports.db');
  const db = new Database(dbPath);
  db.exec(`
    CREATE TABLE ParameterMetadata (
      report_id TEXT, name TEXT, comment TEXT, description TEXT, label TEXT, type TEXT,
      PRIMARY KEY (report_id, name)
    );
    INSERT INTO ParameterMetadata VALUES
      ('report', 'A_Taetigkeit1', '', '', 'Tätigkeit 1', 'select'),
      ('report', 'p_joined_after', '', '', 'Joined after', 'date'),
      ('other', 'A_Taetigkeit1', '', '', 'Tätigkeit 1', 'select');
  `);
  if (withOptionsTable) {
    db.exec(`
      CREATE TABLE ParameterOption (
        report_id TEXT, parameter_name TEXT, position INTEGER, label TEXT, value TEXT,
        PRIMARY KEY (report_id, parameter_name, position)
      );
      INSERT INTO ParameterOption VALUES
        ('report', 'A_Taetigkeit1', 1, 'AK Leiter*in', '176'),
        ('report', 'A_Taetigkeit1', 0, 'Landesbeauftragte*r', '138'),
        ('other', 'A_Taetigkeit1', 0, 'Bundesbeauftragte*r', '42');
    `);
  }
  db.close();
  return dbPath;
}

test('getParameterFor attaches the report-specific options in their declared order', async () => {
  const sut = new SqliteMetadataService(createDb(true));

  const parameters = await sut.getParameterFor('report');

  expect(parameters.find((p) => p.name === 'A_Taetigkeit1')?.options).toEqual([
    { value: '138', label: 'Landesbeauftragte*r' },
    { value: '176', label: 'AK Leiter*in' },
  ]);
});

test('getParameterFor leaves options out for parameters without any', async () => {
  const sut = new SqliteMetadataService(createDb(true));

  const parameters = await sut.getParameterFor('report');

  expect(parameters.find((p) => p.name === 'p_joined_after')).not.toHaveProperty('options');
});

test('getParameterFor works against a reports.db without a ParameterOption table', async () => {
  const sut = new SqliteMetadataService(createDb(false));

  const parameters = await sut.getParameterFor('report');

  expect(parameters.map((p) => p.name).sort()).toEqual(['A_Taetigkeit1', 'p_joined_after']);
  expect(parameters.every((p) => p.options === undefined)).toBe(true);
});
