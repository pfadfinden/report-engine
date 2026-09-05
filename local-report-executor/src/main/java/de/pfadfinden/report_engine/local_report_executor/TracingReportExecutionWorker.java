package de.pfadfinden.report_engine.local_report_executor;

import de.pfadfinden.report_engine.executor.Observability.Logger;
import de.pfadfinden.report_engine.executor.Observability.ReportMetrics;
import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.io.OutputStream;
import java.util.Map;

/**
 * Decorates a ReportExecutionWorker with a report.execution span, audit log, and usage metrics -
 * kept separate from DefaultReportExecutionWorker so that class stays purely about loading,
 * coercing, and filling a report, with observability layered on top instead of inlined into it.
 */
public class TracingReportExecutionWorker implements ReportExecutionWorker {

  private final ReportExecutionWorker delegate;

  public TracingReportExecutionWorker(ReportExecutionWorker delegate) {
    this.delegate = delegate;
  }

  @Override
  public long execute(
      String reportId,
      String executionId,
      Map<String, Object> rawParameters,
      OutputFormat format,
      OutputStream output)
      throws Exception {
    Span span =
        tracer()
            .spanBuilder("report.execution")
            .setAttribute("report.id", reportId)
            .setAttribute("execution.id", executionId)
            .startSpan();
    long startNanos = System.nanoTime();
    try (Scope scope = span.makeCurrent()) {
      long outputSize = delegate.execute(reportId, executionId, rawParameters, format, output);

      double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
      span.setAttribute("status", "success");
      Logger.event(
          "report.execution.completed",
          Attributes.builder()
              .put("report.id", reportId)
              .put("execution.id", executionId)
              .put("status", "success")
              .put("duration.ms", durationMs)
              .put(AttributeKey.longKey("output.size_bytes"), outputSize)
              .build());
      ReportMetrics.recordCompletion(reportId, "success", durationMs, outputSize);
      return outputSize;
    } catch (Throwable t) {
      // Catches Throwable, not just Exception, so an Error (e.g. a class-init failure - see
      // ReportExecutionRunner's original comment on this) still gets recorded here instead of
      // silently skipping the audit/span-error signal on its way past this decorator.
      double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
      span.recordException(t);
      span.setStatus(StatusCode.ERROR);
      Logger.error(
          "report.execution.failed",
          Attributes.builder()
              .put("report.id", reportId)
              .put("execution.id", executionId)
              .put("status", "failure")
              .put("duration.ms", durationMs)
              .build(),
          t);
      ReportMetrics.recordCompletion(reportId, "failure", durationMs, -1);
      if (t instanceof Error error) {
        throw error;
      }
      throw (Exception) t;
    } finally {
      span.end();
    }
  }

  /**
   * Fetched fresh on every call rather than cached in a static field: a Tracer built against a
   * not-yet-registered (no-op) OpenTelemetry instance stays bound to it forever.
   */
  private static Tracer tracer() {
    return GlobalOpenTelemetry.getTracer("de.pfadfinden.report_engine.local_report_executor");
  }
}
