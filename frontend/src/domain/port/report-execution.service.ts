import { ReportExecutionStatus, ReportExecutionTask } from "../model/report-execution-task.model";


export interface ReportExecutionService {

    executeReport(reportExecutionTask: ReportExecutionTask): void;

    status(reportExecutionId: string): ReportExecutionStatus;

    downloadUrl(reportExecutionId: string): string;

}
