package de.pfadfinden.report_engine.azure_report_executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueTrigger;
import de.pfadfinden.report_engine.executor.FillReportService;
import de.pfadfinden.report_engine.executor.Observability.CredentialRedaction;
import de.pfadfinden.report_engine.executor.Observability.TraceContextPropagation;
import de.pfadfinden.report_engine.executor.Observability.TracingReportFiller;
import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import io.opentelemetry.context.Scope;
import java.io.OutputStream;

/**
 * Background worker: consumes a message enqueued by TriggerReportExecutionFunction, fills the
 * report against the reports bundle fetched from mv_reports' released reports.zip (cached locally
 * per function instance) via a ReportFillWorker (see that interface for why it's pulled out
 * separately), uploads the result to blob storage, and records the final status - all durable,
 * since Azure Functions are stateless/scale-out.
 */
public class ExecuteReportFunction {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @FunctionName("ExecuteReportFunction")
  public void run(
      @QueueTrigger(
              name = "execute-report",
              queueName = "report-tasks",
              connection = "AzureWebJobsStorage")
          String message,
      final ExecutionContext context) {
    // Registers the OTel SDK as GlobalOpenTelemetry on first call in this JVM (see
    // Telemetry's javadoc) - has to happen before anything below touches a tracer/meter/logger.
    Telemetry.get();

    String storageConnectionString = System.getenv("AzureWebJobsStorage");
    TableExecutionStatusStore statusStore = new TableExecutionStatusStore(storageConnectionString);
    BlobReportOutputStore outputStore = new BlobReportOutputStore(storageConnectionString);

    ReportExecutionMessage task;
    try {
      task = OBJECT_MAPPER.readValue(message, ReportExecutionMessage.class);
    } catch (Exception e) {
      context.getLogger().severe("Failed to parse report execution message: " + e.getMessage());
      return;
    }

    ReportFillWorker worker =
        new TracingReportFillWorker(
            new DefaultReportFillWorker(
                System.getenv("REPORTS_SOURCE_URL"),
                System.getenv("REPORT_SOURCEDATA_DATABASE_URL"),
                new TracingReportFiller(new FillReportService())));

    try (Scope scope = TraceContextPropagation.extract(task.traceContext()).makeCurrent()) {
      OutputFormat format = OutputFormat.fromValue(task.outputFormat());
      // Azure Storage Queues carry no headers of their own, so the trigger's trace context
      // travels as a field on the message instead (see ReportExecutionMessage) - extracting
      // and activating it here is what makes TracingReportFillWorker's "report.execution"
      // span (created without an explicit parent) continue that trace instead of starting a
      // disconnected new one.
      try (OutputStream output =
          outputStore.open(task.executionId(), format.value(), format.contentType())) {
        worker.execute(task.reportId(), task.executionId(), task.parameter(), format, output);
      }
      statusStore.markDone(task.executionId());
    } catch (Exception e) {
      // TracingReportFillWorker has already recorded a span error + audit event for this by
      // the time it gets here, so this only needs to update durable execution-tracking state.
      // The message is redacted first - a connection failure against the JDBC datasource can
      // otherwise echo its embedded credentials (see docker-compose.yml) straight into this log
      // line and into the execution status record below.
      String safeMessage = CredentialRedaction.redactJdbcCredentials(e.getMessage());
      context
          .getLogger()
          .severe("Failed to execute report " + task.reportId() + ": " + safeMessage);
      statusStore.markFailed(task.executionId(), safeMessage);
    }
  }
}
