package de.pfadfinden.report_engine.local_report_executor;

import de.pfadfinden.report_engine.executor.FillReportService;
import de.pfadfinden.report_engine.executor.Observability.CredentialRedaction;
import de.pfadfinden.report_engine.executor.Observability.TracingReportFiller;
import de.pfadfinden.report_engine.executor.Port.ExecutionStatus;
import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Orchestrates a triggered report fill in the background: resolves the output file, delegates the
 * actual load/coerce/fill/export work to a ReportExecutionWorker (see that interface for why it's
 * pulled out separately), and updates the ExecutionStore with the outcome. Loads the report once
 * (to both fill it and inspect its declared parameter types for coercion), rather than going
 * through FillReportTaskHandler, which would load it a second time internally.
 */
public class ReportExecutionRunner {

  private final File outputDir;
  private final ExecutionStore executionStore;
  private final ExecutorService backgroundExecutor;
  private final ReportExecutionWorker worker;

  public ReportExecutionRunner(
      Config config, ExecutionStore executionStore, ExecutorService backgroundExecutor) {
    this.outputDir = config.outputDir();
    this.executionStore = executionStore;
    this.backgroundExecutor = backgroundExecutor;
    this.worker =
        new TracingReportExecutionWorker(
            new DefaultReportExecutionWorker(
                config.reportsDir(),
                config.sharedAssetsDir(),
                config.datasourceUrl(),
                new TracingReportFiller(new FillReportService())));
  }

  public void runAsync(
      String reportId, String executionId, Map<String, Object> rawParameters, String outputFormat) {
    backgroundExecutor.submit(() -> run(reportId, executionId, rawParameters, outputFormat));
  }

  private void run(
      String reportId,
      String executionId,
      Map<String, Object> rawParameters,
      String outputFormatValue) {
    ExecutionState state = executionStore.get(executionId);
    try {
      OutputFormat format = OutputFormat.fromValue(outputFormatValue);
      state.outputFormat = format;

      outputDir.mkdirs();
      File outputFile = new File(outputDir, executionId + "." + format.value());

      try (OutputStream output = new FileOutputStream(outputFile)) {
        worker.execute(reportId, executionId, rawParameters, format, output);
      }

      state.outputFile = outputFile;
      state.status = ExecutionStatus.DONE;
    } catch (Throwable t) {
      // Runs inside a submitted Runnable whose Future nobody reads, so anything that escapes
      // here would otherwise vanish silently, leaving the execution stuck at PENDING forever
      // with no trace anywhere - TracingReportExecutionWorker has already recorded a span
      // error + audit event for this by the time it gets here, so this only needs to update
      // the local execution-tracking state.
      // Redacted before it's kept around: a JDBC connection failure against the source
      // datasource can echo its embedded credentials (see docker-compose.yml) straight into
      // this message, which the status API then hands back verbatim to any caller.
      state.errorMessage = CredentialRedaction.redactJdbcCredentials(t.getMessage());
      state.status = ExecutionStatus.FAILED;
    }
  }
}
