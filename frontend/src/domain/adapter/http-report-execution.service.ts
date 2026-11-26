import { ReportExecutionService } from '../port/report-execution.service';
import { resilientFetch } from '../../resilience/resilient-fetch';
import { ReportExecutionStatus, ReportExecutionTask } from '../model/report-execution-task.model';

const EXECUTOR_REQUEST_TIMEOUT_MS = 15_000;
const UPSTREAM = 'executor';
const EXECUTION_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function assertValidExecutionId(executionId: string): void {
  if (!EXECUTION_ID_PATTERN.test(executionId)) {
    throw new Error(`Invalid execution id: ${executionId}`);
  }
}

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
    // Triggering is not retried: a replayed POST could start the same report twice.
    const res = await resilientFetch(
      UPSTREAM,
      new URL(`/reports/${task.reportId}/executions`, this.baseUrl),
      {
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
      },
      { timeoutMs: EXECUTOR_REQUEST_TIMEOUT_MS, idempotent: false },
    );
    if (!res.ok) {
      throw new Error(`Failed to trigger report execution: ${res.status}`);
    }
  }

  public async status(executionId: string): Promise<ReportExecutionStatus> {
    assertValidExecutionId(executionId);
    const res = await resilientFetch(
      UPSTREAM,
      new URL(`/executions/${executionId}/status`, this.baseUrl),
      { headers: this.authHeader() },
      { timeoutMs: EXECUTOR_REQUEST_TIMEOUT_MS },
    );
    if (!res.ok) {
      throw new Error(`Failed to fetch execution status: ${res.status}`);
    }
    const { status } = (await res.json()) as {
      status: keyof typeof ReportExecutionStatus;
    };
    return ReportExecutionStatus[status];
  }

  public async downloadUrl(executionId: string): Promise<string> {
    assertValidExecutionId(executionId);
    const res = await resilientFetch(
      UPSTREAM,
      new URL(`/executions/${executionId}/download`, this.baseUrl),
      { headers: this.authHeader() },
      { timeoutMs: EXECUTOR_REQUEST_TIMEOUT_MS },
    );
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
