package de.pfadfinden.report_engine.azure_report_executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import de.pfadfinden.report_engine.executor.Observability.TraceContextPropagation;
import de.pfadfinden.report_engine.executor.Port.ExecutionIdFormat;
import de.pfadfinden.report_engine.executor.Port.ExecutionStatus;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import java.util.Map;
import java.util.Optional;

/**
 * GET /executions/{executionId}/status - part of the shared trigger/status/download API contract.
 */
public class GetExecutionStatusFunction {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @FunctionName("GetExecutionStatus")
  public HttpResponseMessage run(
      @HttpTrigger(
              name = "req",
              methods = {HttpMethod.GET},
              route = "executions/{executionId}/status",
              authLevel = AuthorizationLevel.FUNCTION)
          HttpRequestMessage<Optional<String>> request,
      @BindingName("executionId") String executionId,
      final ExecutionContext context)
      throws Exception {
    Telemetry.get();

    if (!ExecutionIdFormat.isValid(executionId)) {
      return request.createResponseBuilder(HttpStatus.BAD_REQUEST).build();
    }

    Span span =
        GlobalOpenTelemetry.getTracer("de.pfadfinden.report_engine.azure_report_executor")
            .spanBuilder("report.status")
            .setParent(TraceContextPropagation.extract(request.getHeaders()))
            .setAttribute("execution.id", executionId)
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
      String storageConnectionString = System.getenv("AzureWebJobsStorage");
      Optional<ExecutionStatus> status =
          new TableExecutionStatusStore(storageConnectionString).getStatus(executionId);

      if (status.isEmpty()) {
        return request.createResponseBuilder(HttpStatus.NOT_FOUND).build();
      }

      span.setAttribute("status", status.get().name());
      return request
          .createResponseBuilder(HttpStatus.OK)
          .header("Content-Type", "application/json")
          .body(OBJECT_MAPPER.writeValueAsString(Map.of("status", status.get().name())))
          .build();
    } finally {
      span.end();
    }
  }
}
