package de.pfadfinden.reports_engine.azure_report_executor;

import java.util.Map;

/** Body of POST /reports/{reportId}/executions - reportId itself comes from the route. */
public record TriggerReportExecutionRequestBody(String executionId, Map<String, Object> parameter, String outputFormat) {
}
