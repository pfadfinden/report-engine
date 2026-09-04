package de.pfadfinden.report_engine.azure_report_executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueTrigger;
import de.pfadfinden.report_engine.executor.Adapter.Storage.RemoteZip.RemoteZipReportLoader;
import de.pfadfinden.report_engine.executor.FillReportService;
import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import de.pfadfinden.report_engine.executor.Port.ParameterCoercion;
import de.pfadfinden.report_engine.executor.Port.ReportDefinition;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;

/**
 * Background worker: consumes a message enqueued by TriggerReportExecutionFunction, fills the
 * report against the reports bundle fetched from mv_reports' released reports.zip (cached locally
 * per function instance), uploads the result to blob storage, and records the final status - all
 * durable, since Azure Functions are stateless/scale-out.
 */
public class ExecuteReportFunction {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Duration REPORTS_CACHE_TTL = Duration.ofHours(1);

  @FunctionName("ExecuteReportFunction")
  public void run(
      @QueueTrigger(
              name = "execute-report",
              queueName = "report-tasks",
              connection = "AzureWebJobsStorage")
          String message,
      final ExecutionContext context) {
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

    try {
      OutputFormat format = OutputFormat.fromValue(task.outputFormat());

      String reportsSourceUrl = System.getenv("REPORTS_SOURCE_URL");
      RemoteZipReportLoader loader =
          new RemoteZipReportLoader(
              reportsSourceUrl,
              new File(System.getProperty("java.io.tmpdir"), "reports-cache"),
              REPORTS_CACHE_TTL);

      ReportDefinition reportDefinition = loader.load(task.reportId());
      Map<String, Object> parameters =
          ParameterCoercion.coerce(reportDefinition.report, task.parameter());

      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (Connection conn =
          DriverManager.getConnection(System.getenv("REPORT_SOURCEDATA_DATABASE_URL"))) {
        new FillReportService().fill(reportDefinition, parameters, conn, output, format);
      }

      byte[] resultBytes = output.toByteArray();
      outputStore.upload(
          task.executionId(),
          format.value(),
          format.contentType(),
          new ByteArrayInputStream(resultBytes),
          resultBytes.length);
      statusStore.markDone(task.executionId());
    } catch (Exception e) {
      context
          .getLogger()
          .severe("Failed to execute report " + task.reportId() + ": " + e.getMessage());
      statusStore.markFailed(task.executionId(), e.getMessage());
    }
  }
}
