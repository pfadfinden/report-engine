package de.pfadfinden.report_engine.executor.Observability;

import de.pfadfinden.report_engine.executor.Exceptions.FailedToExportReport;
import de.pfadfinden.report_engine.executor.Exceptions.FailedToFillReport;
import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import de.pfadfinden.report_engine.executor.Port.ReportDefinition;
import de.pfadfinden.report_engine.executor.ReportFiller;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.io.OutputStream;
import java.sql.Connection;
import java.util.Map;

/**
 * Decorates a ReportFiller with a report.fill span, kept separate from FillReportService so that
 * class stays purely about JasperReports mechanics, with tracing layered on top as a cross-cutting
 * concern instead of inlined into the fill/export logic.
 */
public class TracingReportFiller implements ReportFiller {

  private final ReportFiller delegate;

  public TracingReportFiller(ReportFiller delegate) {
    this.delegate = delegate;
  }

  @Override
  public void fill(
      ReportDefinition reportDefinition,
      Map<String, Object> parameter,
      Connection conn,
      OutputStream output,
      OutputFormat format)
      throws FailedToFillReport, FailedToExportReport {
    Span span =
        tracer()
            .spanBuilder("report.fill")
            .setAttribute("report.id", reportDefinition.report.getName())
            .setAttribute("output.format", format.value())
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
      delegate.fill(reportDefinition, parameter, conn, output, format);
    } catch (FailedToFillReport | FailedToExportReport e) {
      span.recordException(e);
      span.setStatus(StatusCode.ERROR);
      throw e;
    } finally {
      span.end();
    }
  }

  /**
   * Fetched fresh on every call rather than cached in a static field: a Tracer built against a
   * not-yet-registered (no-op) OpenTelemetry instance stays bound to it forever, and this is a
   * shared library whose class-loading order relative to the host app's Telemetry.init() isn't
   * something it controls.
   */
  private static Tracer tracer() {
    return GlobalOpenTelemetry.getTracer("de.pfadfinden.report_engine.executor");
  }
}
