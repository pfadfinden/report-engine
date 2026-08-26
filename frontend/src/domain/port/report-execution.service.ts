import {
  ReportExecutionStatus,
  ReportExecutionTask,
} from "../model/report-execution-task.model";

export interface ReportExecutionService {
  executeReport(reportExecutionTask: ReportExecutionTask): Promise<void>;

  status(reportExecutionId: string): Promise<ReportExecutionStatus>;

  // Resolves a short-lived, signed download URL for the finished report -
  // requires a network round-trip (never just a local string-builder),
  // since the executor itself signs the URL.
  downloadUrl(reportExecutionId: string): Promise<string>;
}
