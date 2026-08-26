import { Parameter, Report, ReportId } from "../model/report";
import { MetadataService } from "../port/metadata.service";
import Database, { type Database as DatabaseType } from "better-sqlite3";

interface ReportDto {
  id: string;
  title: string;
  description: string;
  version: string;
  complex: number;
  outputFormats: string;
}

export class SqliteMetadataService implements MetadataService {
  private _db: DatabaseType | undefined;

  constructor(private readonly sqliteDbPath: string) {}

  getParameterFor(reportId: ReportId): Promise<readonly Parameter[]> {
    return this._all<Parameter>(
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
                    ( 
                      SELECT v.version 
                      FROM VersionMetadata v 
                      WHERE v.report_id = r.id
                      ORDER BY v.createdOn DESC
                      LIMIT 1
                    ) as version
                FROM Reports r
            `,
    ).then((rows) => rows.map(this._mapReport));
  }

  private _all<T, P extends object = object>(
    sql: string,
    params?: P,
  ): Promise<ReadonlyArray<T>> {
    return Promise.resolve(this.db.prepare<P, T>(sql).all(params ?? {}));
  }

  private _mapReport(dbReport: ReportDto): Report {
    return {
      ...dbReport,
      complex: dbReport.complex === 1,
      outputFormats: JSON.parse(dbReport.outputFormats),
    };
  }

  private get db() {
    if (!this._db) {
      this._db = new Database(this.sqliteDbPath, { readonly: true });
    }
    return this._db;
  }
}
