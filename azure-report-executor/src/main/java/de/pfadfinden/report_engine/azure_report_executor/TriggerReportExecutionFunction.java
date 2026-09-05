package de.pfadfinden.report_engine.azure_report_executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.azure.functions.annotation.QueueOutput;
import de.pfadfinden.report_engine.executor.Observability.AuditAttributes;
import de.pfadfinden.report_engine.executor.Observability.Logger;
import de.pfadfinden.report_engine.executor.Observability.ReportMetrics;
import de.pfadfinden.report_engine.executor.Observability.TraceContextPropagation;
import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.Optional;

/**
 * HTTP entry point of the trigger/status/download API contract shared with local-report-executor:
 * POST /reports/{reportId}/executions. Records the execution as PENDING and hands the actual fill
 * off to the queue-triggered ExecuteReportFunction worker, returning immediately.
 */
public class TriggerReportExecutionFunction {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @FunctionName("TriggerReportExecution")
  public HttpResponseMessage run(
      @HttpTrigger(
              name = "req",
              methods = {HttpMethod.POST},
              route = "reports/{reportId}/executions",
              authLevel = AuthorizationLevel.FUNCTION)
          HttpRequestMessage<Optional<String>> request,
      @BindingName("reportId") String reportId,
      @QueueOutput(name = "message", queueName = "report-tasks", connection = "AzureWebJobsStorage")
          OutputBinding<String> queueMessage,
      final ExecutionContext context) {
    // Registers the OTel SDK as GlobalOpenTelemetry on first call in this JVM (see
    // Telemetry's javadoc) - has to happen before anything below touches a tracer/meter/logger.
    Telemetry.get();

    TriggerReportExecutionRequestBody body;
    try {
      body =
          OBJECT_MAPPER.readValue(
              request.getBody().orElse(""), TriggerReportExecutionRequestBody.class);
    } catch (Exception e) {
      return request
          .createResponseBuilder(HttpStatus.BAD_REQUEST)
          .body("Invalid request body: " + e.getMessage())
          .build();
    }

    // Validated synchronously rather than left for ExecuteReportFunction to discover: without
    // this, an unsupported outputFormat still returns 202 here and only surfaces later as an
    // async FAILED status, instead of an immediate, actionable 400.
    try {
      OutputFormat.fromValue(body.outputFormat());
    } catch (IllegalArgumentException e) {
      return request
          .createResponseBuilder(HttpStatus.BAD_REQUEST)
          .body("Unsupported outputFormat: " + body.outputFormat())
          .build();
    }

    Span span =
        io.opentelemetry.api.GlobalOpenTelemetry.getTracer(
                "de.pfadfinden.report_engine.azure_report_executor")
            .spanBuilder("report.trigger")
            .setParent(TraceContextPropagation.extract(request.getHeaders()))
            .setAttribute("report.id", reportId)
            .setAttribute("execution.id", body.executionId())
            .setAttribute("output.format", body.outputFormat())
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
      String storageConnectionString = System.getenv("AzureWebJobsStorage");
      new TableExecutionStatusStore(storageConnectionString)
          .putPending(body.executionId(), body.outputFormat());

      ReportExecutionMessage message =
          new ReportExecutionMessage(
              body.executionId(),
              reportId,
              body.parameter(),
              body.outputFormat(),
              TraceContextPropagation.injectCurrent());
      queueMessage.setValue(OBJECT_MAPPER.writeValueAsString(message));

      AttributesBuilder attributes =
          Attributes.builder()
              .put("report.id", reportId)
              .put("execution.id", body.executionId())
              .put("output.format", body.outputFormat());
      AuditAttributes.extractGroupId(body.parameter())
          .ifPresent(groupId -> attributes.put("group.id", groupId));
      Logger.event("report.trigger.requested", attributes.build());
      ReportMetrics.recordTrigger(reportId);

      return request.createResponseBuilder(HttpStatus.ACCEPTED).build();
    } catch (Exception e) {
      context.getLogger().severe("Failed to trigger report execution: " + e.getMessage());
      span.recordException(e);
      span.setStatus(StatusCode.ERROR);
      Logger.error(
          "report.trigger.failed",
          Attributes.of(
              AttributeKey.stringKey("report.id"),
              reportId,
              AttributeKey.stringKey("execution.id"),
              body.executionId()),
          e);
      return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).build();
    } finally {
      span.end();
    }
  }
}
