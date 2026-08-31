package de.pfadfinden.reports_engine.local_report_executor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import de.pfadfinden.reports_engine.executor.Adapter.Storage.Filesystem.LocalFilesystemReportLoader;
import de.pfadfinden.reports_engine.executor.FillReportService;
import de.pfadfinden.reports_engine.executor.Port.ExecutionStatus;
import de.pfadfinden.reports_engine.executor.Port.OutputFormat;
import de.pfadfinden.reports_engine.executor.Port.ParameterCoercion;
import de.pfadfinden.reports_engine.executor.Port.ReportDefinition;

/**
 * Runs a triggered report fill in the background and updates the
 * ExecutionStore with the outcome. Loads the report once (to both fill it
 * and inspect its declared parameter types for coercion), rather than going
 * through FillReportTaskHandler, which would load it a second time
 * internally.
 */
public class ReportExecutionRunner {

    private final File reportsDir;
    private final File sharedAssetsDir;
    private final File outputDir;
    private final String datasourceUrl;
    private final ExecutionStore executionStore;
    private final ExecutorService backgroundExecutor;

    public ReportExecutionRunner(Config config, ExecutionStore executionStore, ExecutorService backgroundExecutor) {
        this.reportsDir = config.reportsDir();
        this.sharedAssetsDir = config.sharedAssetsDir();
        this.outputDir = config.outputDir();
        this.datasourceUrl = config.datasourceUrl();
        this.executionStore = executionStore;
        this.backgroundExecutor = backgroundExecutor;
    }

    public void runAsync(String reportId, String executionId, Map<String, Object> rawParameters, String outputFormat) {
        backgroundExecutor.submit(() -> run(reportId, executionId, rawParameters, outputFormat));
    }

    private void run(String reportId, String executionId, Map<String, Object> rawParameters, String outputFormatValue) {
        ExecutionState state = executionStore.get(executionId);
        try {
            OutputFormat format = OutputFormat.fromValue(outputFormatValue);
            state.outputFormat = format;

            LocalFilesystemReportLoader loader = new LocalFilesystemReportLoader(reportsDir, sharedAssetsDir);
            ReportDefinition reportDefinition = loader.load(reportId);
            Map<String, Object> parameters = ParameterCoercion.coerce(reportDefinition.report, rawParameters);

            outputDir.mkdirs();
            File outputFile = new File(outputDir, executionId + "." + format.value());

            try (Connection conn = DriverManager.getConnection(datasourceUrl);
                    OutputStream output = new FileOutputStream(outputFile)) {
                new FillReportService().fill(reportDefinition, parameters, conn, output, format);
            }

            state.outputFile = outputFile;
            state.status = ExecutionStatus.DONE;
        } catch (Throwable t) {
            // Runs inside a submitted Runnable whose Future nobody reads, so
            // anything that escapes here (including an Error - e.g. a class
            // init failure from a prior execution poisoning AWT's font
            // manager, see Dockerfile) vanishes silently, leaving the
            // execution stuck at PENDING forever with no trace in the logs.
            t.printStackTrace();
            state.errorMessage = t.getMessage();
            state.status = ExecutionStatus.FAILED;
        }
    }
}
