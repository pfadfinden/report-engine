package de.pfadfinden.report_engine.local_report_executor;

import de.pfadfinden.report_engine.executor.Adapter.Storage.Filesystem.LocalFilesystemReportLoader;
import de.pfadfinden.report_engine.executor.Observability.CountingOutputStream;
import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import de.pfadfinden.report_engine.executor.Port.ParameterCoercion;
import de.pfadfinden.report_engine.executor.Port.ReportDefinition;
import de.pfadfinden.report_engine.executor.ReportFiller;
import java.io.File;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

/**
 * Pure JasperReports mechanics - no tracing/audit/metrics, that's TracingReportExecutionWorker's
 * job. Takes a ReportFiller as a dependency rather than constructing FillReportService itself, so
 * the caller decides whether the inner fill step also gets its own (nested) report.fill span.
 */
public class DefaultReportExecutionWorker implements ReportExecutionWorker {

  private final File reportsDir;
  private final File sharedAssetsDir;
  private final String datasourceUrl;
  private final ReportFiller reportFiller;

  public DefaultReportExecutionWorker(
      File reportsDir, File sharedAssetsDir, String datasourceUrl, ReportFiller reportFiller) {
    this.reportsDir = reportsDir;
    this.sharedAssetsDir = sharedAssetsDir;
    this.datasourceUrl = datasourceUrl;
    this.reportFiller = reportFiller;
  }

  @Override
  public long execute(
      String reportId,
      String executionId,
      Map<String, Object> rawParameters,
      OutputFormat format,
      OutputStream output)
      throws Exception {
    LocalFilesystemReportLoader loader =
        new LocalFilesystemReportLoader(reportsDir, sharedAssetsDir);
    ReportDefinition reportDefinition = loader.load(reportId);
    Map<String, Object> parameters =
        ParameterCoercion.coerce(reportDefinition.report, rawParameters);

    try (Connection conn = DriverManager.getConnection(datasourceUrl);
        CountingOutputStream counting = new CountingOutputStream(output)) {
      reportFiller.fill(reportDefinition, parameters, conn, counting, format);
      return counting.bytesWritten();
    }
  }
}
