import { Parameter, Report, ReportId } from '../model/report';

export interface MetadataService {
  findFor(groupType: string): Promise<ReadonlyArray<Report>>;

  /** Reports flagged `individualPersonReport: true` in their metadata.yaml, regardless of group type. */
  findIndividualPersonReports(): Promise<ReadonlyArray<Report>>;

  getParameterFor(reportId: ReportId): Promise<ReadonlyArray<Parameter>>;
}
