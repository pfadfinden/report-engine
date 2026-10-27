import { Parameter, Report, ReportId } from '../model/report';
import { MetadataService } from '../port/metadata.service';
import Database, { type Database as DatabaseType } from 'better-sqlite3';
import * as logger from '../../telemetry/logger';

interface ReportDto {
  id: string;
  title: string;
  description: string;
  version: string;
  complex: number;
  outputFormats: string;
  onlyForType: string | null;
}

const OPTIONS_TABLE_EXISTS_SQL = `SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'ParameterOption'`;

interface ParameterOptionDto {
  parameter_name: string;
  value: string;
  label: string;
}

export class SqliteMetadataService implements MetadataService {
  private _db: DatabaseType | undefined;

  constructor(private readonly sqliteDbPath: string) {}

  async getParameterFor(reportId: ReportId): Promise<readonly Parameter[]> {
    const [parameters, options] = await Promise.all([
      this._all<Parameter>(
        `
                SELECT
                    p.name,
                    p.label,
                    p.description,
                    p.type
                FROM ParameterMetadata p
                WHERE p.report_id = $reportId;
            `,
        { reportId },
      ),
      this._optionsFor(reportId),
    ]);

    return parameters.map((parameter) => {
      const parameterOptions = options
        .filter((option) => option.parameter_name === parameter.name)
        .map(({ value, label }) => ({ value, label }));
      return parameterOptions.length > 0 ? { ...parameter, options: parameterOptions } : parameter;
    });
  }

  private _optionsFor(reportId: ReportId): Promise<ReadonlyArray<ParameterOptionDto>> {
    // A reports.db built by a preprocessor from before select parameters existed has no
    // ParameterOption table at all - that just means no parameter has options, not an error.
    if (this.db.prepare(OPTIONS_TABLE_EXISTS_SQL).get() === undefined) {
      return Promise.resolve([]);
    }
    return this._all<ParameterOptionDto>(
      `
                SELECT
                    o.parameter_name,
                    o.value,
                    o.label
                FROM ParameterOption o
                WHERE o.report_id = $reportId
                ORDER BY o.parameter_name, o.position;
            `,
      { reportId },
    );
  }

  findFor(groupType: string): Promise<ReadonlyArray<Report>> {
    return this._all<ReportDto>(
      `
                SELECT
                    r.id,
                    r.title,
                    r.description,
                    r.complex,
                    r.outputFormats,
                    r.onlyForType,
                    (
                      SELECT v.version
                      FROM VersionMetadata v
                      WHERE v.report_id = r.id
                      ORDER BY v.createdOn DESC
                      LIMIT 1
                    ) as version
                FROM Reports r
            `,
    )
      .then((rows) => rows.map(this._mapReport))
      .then((reports) =>
        groupType === '*'
          ? reports
          : reports.filter((report) => report.onlyForType.length === 0 || report.onlyForType.includes(groupType)),
      );
  }

  private _all<T, P extends object = object>(sql: string, params?: P): Promise<ReadonlyArray<T>> {
    return Promise.resolve(this.db.prepare<P, T>(sql).all(params ?? {}));
  }

  private _mapReport(dbReport: ReportDto): Report {
    return {
      ...dbReport,
      complex: dbReport.complex === 1,
      outputFormats: JSON.parse(dbReport.outputFormats),
      onlyForType: (dbReport.onlyForType && JSON.parse(dbReport.onlyForType)) || [],
    };
  }

  private get db() {
    if (!this._db) {
      try {
        this._db = new Database(this.sqliteDbPath, { readonly: true });
      } catch (err) {
        logger.error('metadata.db.open_failed', { 'db.path': this.sqliteDbPath }, err);
        throw err;
      }
    }
    return this._db;
  }
}
