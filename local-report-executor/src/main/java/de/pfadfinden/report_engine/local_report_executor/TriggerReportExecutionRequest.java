package de.pfadfinden.report_engine.local_report_executor;

import java.util.Map;

public record TriggerReportExecutionRequest(
    String executionId, Map<String, Object> parameter, String outputFormat) {}
