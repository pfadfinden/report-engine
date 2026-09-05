package de.pfadfinden.report_engine.executor.Observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;

/**
 * Usage-statistics instruments shared by both deployments. Fetches a fresh Meter from
 * GlobalOpenTelemetry on every call rather than caching instruments in static fields: an instrument
 * built against a not-yet-registered (no-op) OpenTelemetry instance stays bound to it forever, so
 * caching would silently and permanently lose data if this class ever loaded before
 * Telemetry.init() ran. The low request volume here (report generation, not a hot API path) makes a
 * fresh Meter/instrument lookup per call fully negligible.
 */
public final class ReportMetrics {

  private static final AttributeKey<String> REPORT_ID = AttributeKey.stringKey("report.id");
  private static final AttributeKey<String> STATUS = AttributeKey.stringKey("status");

  private ReportMetrics() {}

  public static void recordTrigger(String reportId) {
    meter()
        .counterBuilder("report.executions.triggered")
        .setDescription("Report executions requested")
        .build()
        .add(1, Attributes.of(REPORT_ID, reportId));
  }

  public static void recordCompletion(
      String reportId, String status, double durationMs, long outputSizeBytes) {
    Meter meter = meter();
    Attributes reportAndStatus = Attributes.of(REPORT_ID, reportId, STATUS, status);

    meter
        .counterBuilder("report.executions.completed")
        .setDescription("Report executions finished, by status")
        .build()
        .add(1, reportAndStatus);
    meter
        .histogramBuilder("report.execution.duration")
        .setDescription("Time to fill and export a report")
        .setUnit("ms")
        .build()
        .record(durationMs, reportAndStatus);
    if (outputSizeBytes >= 0) {
      meter
          .histogramBuilder("report.output.size")
          .setDescription("Size of the exported report output")
          .setUnit("By")
          .build()
          .record(outputSizeBytes, Attributes.of(REPORT_ID, reportId));
    }
  }

  public static void recordDownload(String reportId) {
    meter()
        .counterBuilder("report.downloads")
        .setDescription("Report output files downloaded")
        .build()
        .add(1, Attributes.of(REPORT_ID, reportId));
  }

  private static Meter meter() {
    return GlobalOpenTelemetry.getMeter("report-engine");
  }
}
