import { ReportExecutionService } from '../port/report-execution.service';
import { ReportExecutionStatus, ReportExecutionTask } from '../model/report-execution-task.model';

// These are all small, synchronous-by-design API calls (trigger returns 202 immediately, status/
// download-url are single lookups) - a fixed, generous timeout is enough to stop a hung executor
// from hanging every request that talks to it forever.
const EXECUTOR_REQUEST_TIMEOUT_MS = 15_000;

/**
 * Calls the shared trigger/status/download HTTP API contract exposed by
 * both local-report-executor and the Azure Functions executor - one
 * adapter works against either, since they expose the same API shape; only
 * the configured base URL differs between environments.
 */
export class HttpReportExecutionService implements ReportExecutionService {
  constructor(
    private readonly baseUrl: string,
    private readonly apiToken?: string,
  ) {}

  public async executeReport(task: ReportExecutionTask): Promise<void> {
    const res = await fetch(new URL(`/reports/${task.reportId}/executions`, this.baseUrl), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...this.authHeader(),
      },
      body: JSON.stringify({
        executionId: task.executionId,
        parameter: task.parameter,
        outputFormat: task.outputFormat,
      }),
      signal: AbortSignal.timeout(EXECUTOR_REQUEST_TIMEOUT_MS),
    });
    if (!res.ok) {
      throw new Error(`Failed to trigger report execution: ${res.status}`);
    }
  }

  public async status(executionId: string): Promise<ReportExecutionStatus> {
    const res = await fetch(new URL(`/executions/${executionId}/status`, this.baseUrl), {
      headers: this.authHeader(),
      signal: AbortSignal.timeout(EXECUTOR_REQUEST_TIMEOUT_MS),
    });
    if (!res.ok) {
      throw new Error(`Failed to fetch execution status: ${res.status}`);
    }
    const { status } = (await res.json()) as {
      status: keyof typeof ReportExecutionStatus;
    };
    return ReportExecutionStatus[status];
  }

  public async downloadUrl(executionId: string): Promise<string> {
    const res = await fetch(new URL(`/executions/${executionId}/download`, this.baseUrl), {
      headers: this.authHeader(),
      signal: AbortSignal.timeout(EXECUTOR_REQUEST_TIMEOUT_MS),
    });
    if (!res.ok) {
      throw new Error(`Failed to fetch download URL: ${res.status}`);
    }
    const { url } = (await res.json()) as { url: string };
    return url;
  }

  private authHeader(): Record<string, string> {
    return this.apiToken ? { Authorization: `Bearer ${this.apiToken}` } : {};
  }
}
