import { ReportId } from "./report";

export enum ReportExecutionStatus {
    PENDING,
    DONE,
    FAILED,
}

export interface ReportExecutionTask {
    readonly executionId: string;
    readonly reportId: ReportId;
    readonly parameter: object;
}