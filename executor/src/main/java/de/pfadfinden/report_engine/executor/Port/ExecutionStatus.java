package de.pfadfinden.report_engine.executor.Port;

/**
 * Status of an asynchronous report execution, shared by every HTTP-facing executor
 * (local-report-executor, azure-report-executor) implementing the common trigger/status/download
 * API contract.
 */
public enum ExecutionStatus {
  PENDING,
  DONE,
  FAILED
}
