package de.pfadfinden.report_engine.executor;

import de.pfadfinden.report_engine.executor.Exceptions.FailedToExportReport;
import de.pfadfinden.report_engine.executor.Exceptions.FailedToFillReport;
import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import de.pfadfinden.report_engine.executor.Port.ReportDefinition;
import java.io.OutputStream;
import java.sql.Connection;
import java.util.Map;

/**
 * Fills a report's data and exports it to the given format/stream. Pulled out as an interface so
 * cross-cutting concerns (tracing - see Observability.TracingReportFiller) can decorate
 * FillReportService instead of being inlined into its JasperReports mechanics.
 */
public interface ReportFiller {
  void fill(
      ReportDefinition reportDefinition,
      Map<String, Object> parameter,
      Connection conn,
      OutputStream output,
      OutputFormat format)
      throws FailedToFillReport, FailedToExportReport;
}
