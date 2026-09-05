package de.pfadfinden.report_engine.azure_report_executor;

import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import java.io.OutputStream;
import java.util.Map;

/**
 * Loads, coerces, and fills one report, writing the exported bytes to the given stream. Pulled out
 * from ExecuteReportFunction as an interface so tracing (see TracingReportFillWorker) can decorate
 * it instead of being inlined alongside the actual execution work.
 */
public interface ReportFillWorker {

  /**
   * Returns the number of bytes written to output. Throws (including, in principle, an Error) on
   * any failure to load, coerce, fill, or export.
   */
  long execute(
      String reportId,
      String executionId,
      Map<String, Object> rawParameters,
      OutputFormat format,
      OutputStream output)
      throws Exception;
}
