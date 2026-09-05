package de.pfadfinden.report_engine.local_report_executor;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingLogRecordExporter;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import java.util.Map;

/**
 * Registers the OpenTelemetry SDK as the process-wide GlobalOpenTelemetry, read by executor's
 * FillReportService and this module's own instrumentation. Uses the standard OTel autoconfigure
 * mechanism (OTEL_* env vars - see https://opentelemetry.io/docs/languages/sdk-configuration/) so
 * an operator can point this at any OTLP-compatible collector without a code change.
 *
 * <p>If none of the OTLP endpoint env vars are set, traces and metrics are disabled outright (not
 * created at all, not even to console) rather than defaulting to autoconfigure's own "otlp against
 * localhost:4317" (which would silently fail to export) or a console dump: both are opt-in
 * debugging/analysis aids with their own overhead and noise, so they stay off until an operator
 * deliberately turns them on - by pointing OTEL_EXPORTER_OTLP_ENDPOINT/etc. at a real collector, or
 * by setting OTEL_TRACES_EXPORTER=console/OTEL_METRICS_EXPORTER=console themselves for local
 * visibility without one. Logs (the audit trail this was actually built for) are the exception:
 * they stay on by default via ConsoleLogRecordExporter (see that class for why not the SDK's own
 * "logging" exporter) - text by default, or real OTLP/JSON (via OtlpJsonLoggingLogRecordExporter,
 * not a hand-rolled format) if LOG_CONSOLE_FORMAT=json. An explicit OTEL_TRACES_EXPORTER/etc.
 * always wins over these defaults.
 */
public final class Telemetry {

  private Telemetry() {}

  public static void init() {
    OpenTelemetrySdk sdk = buildSdk();
    GlobalOpenTelemetry.set(sdk);
    // Gives the OpenTelemetryAppender configured in logback.xml the actual SDK instance to
    // route Javalin's/Jetty's own SLF4J logging into - it can't just read GlobalOpenTelemetry
    // itself (Logback initializes before this method runs).
    OpenTelemetryAppender.install(sdk);
  }

  private static OpenTelemetrySdk buildSdk() {
    boolean endpointConfigured =
        System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") != null
            || System.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT") != null
            || System.getenv("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT") != null
            || System.getenv("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT") != null;

    var builder =
        AutoConfiguredOpenTelemetrySdk.builder()
            .addPropertiesSupplier(() -> Map.of("otel.service.name", "local-report-executor"));

    if (!endpointConfigured) {
      builder.addPropertiesSupplier(
          () ->
              Map.of(
                  "otel.traces.exporter", "none",
                  "otel.metrics.exporter", "none",
                  // A real exporter still has to be selected here so there's something for
                  // the customizer below to replace - "logging" itself is discarded, only
                  // its plumbing (a SimpleLogRecordProcessor) is reused.
                  "otel.logs.exporter", "logging"));
      builder.addLogRecordExporterCustomizer(
          (exporter, config) ->
              "json".equalsIgnoreCase(System.getenv("LOG_CONSOLE_FORMAT"))
                  ? OtlpJsonLoggingLogRecordExporter.create()
                  : new ConsoleLogRecordExporter());
    }

    return builder.build().getOpenTelemetrySdk();
  }
}
