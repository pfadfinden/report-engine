package de.pfadfinden.report_engine.local_report_executor;

import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import java.io.OutputStream;
import java.util.Map;

/**
 * Loads, fills, and exports one report. Pulled out from ReportExecutionRunner as an interface so
 * tracing (see TracingReportExecutionWorker) can decorate it instead of being inlined alongside the
 * actual execution work.
 */
public interface ReportExecutionWorker {

  /**
   * Returns the number of bytes written to output. Throws (including, in principle, an Error - e.g.
   * a class-init failure) on any failure to load, coerce, fill, or export the report.
   */
  long execute(
      String reportId,
      String executionId,
      Map<String, Object> rawParameters,
      OutputFormat format,
      OutputStream output)
      throws Exception;
}
