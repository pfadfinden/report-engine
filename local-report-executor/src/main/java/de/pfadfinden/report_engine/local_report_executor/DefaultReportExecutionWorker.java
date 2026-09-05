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

public class DefaultReportExecutionWorker implements ReportExecutionWorker {

  // A JVM-global setting; without it, an unreachable source DB hangs DriverManager.getConnection
  // indefinitely, eventually exhausting the fixed-size background thread pool (see Main).
  private static final int CONNECTION_TIMEOUT_SECONDS = 10;

  static {
    DriverManager.setLoginTimeout(CONNECTION_TIMEOUT_SECONDS);
  }

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
