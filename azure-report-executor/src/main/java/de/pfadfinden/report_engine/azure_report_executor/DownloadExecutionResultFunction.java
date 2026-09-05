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
import de.pfadfinden.report_engine.executor.Observability.Logger;
import de.pfadfinden.report_engine.executor.Observability.TraceContextPropagation;
import de.pfadfinden.report_engine.executor.Port.ExecutionStatus;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import java.util.Map;
import java.util.Optional;

/**
 * GET /executions/{executionId}/download - part of the shared trigger/ status/download API
 * contract. Always returns { "url": ... }, a SAS-signed blob URL, never the bytes and never a
 * redirect - matches local-report-executor's equivalent endpoint exactly.
 */
public class DownloadExecutionResultFunction {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @FunctionName("DownloadExecutionResult")
  public HttpResponseMessage run(
      @HttpTrigger(
              name = "req",
              methods = {HttpMethod.GET},
              route = "executions/{executionId}/download",
              authLevel = AuthorizationLevel.FUNCTION)
          HttpRequestMessage<Optional<String>> request,
      @BindingName("executionId") String executionId,
      final ExecutionContext context)
      throws Exception {
    // Registers the OTel SDK as GlobalOpenTelemetry on first call in this JVM (see
    // Telemetry's javadoc) - has to happen before anything below touches a tracer/logger.
    Telemetry.get();

    Span span =
        GlobalOpenTelemetry.getTracer("de.pfadfinden.report_engine.azure_report_executor")
            .spanBuilder("report.download.link_issued")
            .setParent(TraceContextPropagation.extract(request.getHeaders()))
            .setAttribute("execution.id", executionId)
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
      String storageConnectionString = System.getenv("AzureWebJobsStorage");
      TableExecutionStatusStore statusStore =
          new TableExecutionStatusStore(storageConnectionString);
      Optional<ExecutionStatus> status = statusStore.getStatus(executionId);

      if (status.isEmpty()) {
        return request.createResponseBuilder(HttpStatus.NOT_FOUND).build();
      }
      if (status.get() != ExecutionStatus.DONE) {
        return request
            .createResponseBuilder(HttpStatus.CONFLICT)
            .header("Content-Type", "application/json")
            .body(OBJECT_MAPPER.writeValueAsString(Map.of("status", status.get().name())))
            .build();
      }

      // Set by TriggerReportExecutionFunction and never touched again (see
      // TableExecutionStatusStore's merge-semantics note) - always present once DONE.
      String extension = statusStore.getOutputFormat(executionId).orElseThrow();
      String url =
          new BlobReportOutputStore(storageConnectionString)
              .generateDownloadUrl(executionId, extension);

      // This only covers SAS URL issuance, not the actual download: the caller
      // fetches bytes directly from Blob Storage afterward, invisible to this Java
      // codebase. True per-download auditing for this deployment would need Blob
      // Storage diagnostic logging (out of scope here) - so this is a weaker
      // signal than local-report-executor's report.download event, which fires
      // at the point bytes are actually served.
      Logger.event(
          "report.download.link_issued",
          Attributes.of(AttributeKey.stringKey("execution.id"), executionId));

      return request
          .createResponseBuilder(HttpStatus.OK)
          .header("Content-Type", "application/json")
          .body(OBJECT_MAPPER.writeValueAsString(Map.of("url", url)))
          .build();
    } finally {
      span.end();
    }
  }
}
