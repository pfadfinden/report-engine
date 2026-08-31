import { Parameter, Report, ReportId } from '../model/report';

export interface MetadataService {
  findFor(groupType: string): Promise<ReadonlyArray<Report>>;

  getParameterFor(reportId: ReportId): Promise<ReadonlyArray<Parameter>>;
}
