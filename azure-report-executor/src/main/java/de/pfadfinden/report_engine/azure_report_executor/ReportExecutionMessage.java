package de.pfadfinden.report_engine.azure_report_executor;

import java.util.Map;

/**
 * Payload enqueued onto the "report-tasks" queue by the trigger function and consumed by the
 * worker. traceContext carries the trigger's W3C trace context (see TraceContextPropagation) -
 * Azure Storage Queues have no header mechanism of their own, so without this,
 * ExecuteReportFunction's spans would start a disconnected new trace instead of continuing the
 * caller's.
 */
public record ReportExecutionMessage(
    String executionId,
    String reportId,
    Map<String, Object> parameter,
    String outputFormat,
    Map<String, String> traceContext) {}
