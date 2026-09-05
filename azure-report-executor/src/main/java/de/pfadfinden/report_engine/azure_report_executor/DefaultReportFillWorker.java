package de.pfadfinden.report_engine.azure_report_executor;

import de.pfadfinden.report_engine.executor.Adapter.Storage.RemoteZip.RemoteZipReportLoader;
import de.pfadfinden.report_engine.executor.Observability.CountingOutputStream;
import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import de.pfadfinden.report_engine.executor.Port.ParameterCoercion;
import de.pfadfinden.report_engine.executor.Port.ReportDefinition;
import de.pfadfinden.report_engine.executor.ReportFiller;
import java.io.File;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;

/**
 * Pure JasperReports mechanics - no tracing/audit/metrics, that's TracingReportFillWorker's job.
 * Takes a ReportFiller as a dependency rather than constructing FillReportService itself, so the
 * caller decides whether the inner fill step also gets its own (nested) report.fill span.
 */
public class DefaultReportFillWorker implements ReportFillWorker {

  private static final Duration REPORTS_CACHE_TTL = Duration.ofHours(1);

  // A JVM-global setting; without it, an unreachable source DB hangs DriverManager.getConnection
  // indefinitely.
  private static final int CONNECTION_TIMEOUT_SECONDS = 10;

  static {
    DriverManager.setLoginTimeout(CONNECTION_TIMEOUT_SECONDS);
  }

  private final String reportsSourceUrl;
  private final String datasourceUrl;
  private final ReportFiller reportFiller;

  public DefaultReportFillWorker(
      String reportsSourceUrl, String datasourceUrl, ReportFiller reportFiller) {
    this.reportsSourceUrl = reportsSourceUrl;
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
    RemoteZipReportLoader loader =
        new RemoteZipReportLoader(
            reportsSourceUrl,
            new File(System.getProperty("java.io.tmpdir"), "reports-cache"),
            REPORTS_CACHE_TTL);

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
