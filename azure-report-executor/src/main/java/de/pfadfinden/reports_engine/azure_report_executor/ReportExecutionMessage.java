package de.pfadfinden.reports_engine.azure_report_executor;

import java.util.Map;

/**
 * Payload enqueued onto the "report-tasks" queue by the trigger function and consumed by the
 * worker.
 */
public record ReportExecutionMessage(
    String executionId, String reportId, Map<String, Object> parameter, String outputFormat) {}
