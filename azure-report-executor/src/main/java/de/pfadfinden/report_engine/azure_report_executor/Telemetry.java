package de.pfadfinden.report_engine.azure_report_executor;

import com.azure.monitor.opentelemetry.autoconfigure.AzureMonitorAutoConfigure;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingLogRecordExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import java.util.Map;

/**
 * Registers the OpenTelemetry SDK as the process-wide GlobalOpenTelemetry, read by executor's
 * FillReportService and this module's own instrumentation.
 *
 * <p>Azure Functions instantiates a new function class per invocation rather than calling a single
 * app entrypoint, so - unlike local-report-executor's Main.main() - there's no natural place to run
 * this once at startup. INSTANCE is lazily initialized on first access instead (a plain static
 * final field, guarded by the JVM's normal class-init-once semantics), and every function class
 * calls Telemetry.get() rather than constructing its own.
 */
public final class Telemetry {

  private static final OpenTelemetry INSTANCE = init();

  private Telemetry() {}

  public static OpenTelemetry get() {
    return INSTANCE;
  }

  private static OpenTelemetry init() {
    String connectionString = System.getenv("APPLICATIONINSIGHTS_CONNECTION_STRING");

    OpenTelemetrySdk sdk;
    if (connectionString != null && !connectionString.isBlank()) {
      AutoConfiguredOpenTelemetrySdkBuilder builder =
          AutoConfiguredOpenTelemetrySdk.builder()
              .addPropertiesSupplier(() -> Map.of("otel.service.name", "azure-report-executor"));
      AzureMonitorAutoConfigure.customize(builder, connectionString);
      sdk = builder.build().getOpenTelemetrySdk();
    } else {
      // No connection string configured: traces and metrics are disabled outright (not
      // created at all, not even to console) rather than defaulting to autoconfigure's own
      // "otlp against localhost:4317" (which would silently fail to export here) - see
      // local-report-executor's Telemetry for the same reasoning. Both are opt-in
      // debugging/analysis aids with their own overhead and noise, so they stay off until an
      // operator deliberately enables them - by setting the connection string above, or
      // OTEL_TRACES_EXPORTER=console/OTEL_METRICS_EXPORTER=console themselves for local
      // visibility without one. Logs (the audit trail this was actually built for) are the
      // exception: they stay on by default via ConsoleLogRecordExporter (see that class for
      // why not the SDK's own "logging" exporter) - text by default, or real OTLP/JSON (via
      // OtlpJsonLoggingLogRecordExporter, not a hand-rolled format) if LOG_CONSOLE_FORMAT=json.
      AutoConfiguredOpenTelemetrySdkBuilder builder =
          AutoConfiguredOpenTelemetrySdk.builder()
              .addPropertiesSupplier(
                  () ->
                      Map.of(
                          "otel.service.name", "azure-report-executor",
                          "otel.traces.exporter", "none",
                          "otel.metrics.exporter", "none",
                          // A real exporter still has to be selected here so there's
                          // something for the customizer below to replace - "logging" itself
                          // is discarded, only its plumbing (a SimpleLogRecordProcessor) is
                          // reused.
                          "otel.logs.exporter", "logging"));
      builder.addLogRecordExporterCustomizer(
          (exporter, config) ->
              "json".equalsIgnoreCase(System.getenv("LOG_CONSOLE_FORMAT"))
                  ? OtlpJsonLoggingLogRecordExporter.create()
                  : new ConsoleLogRecordExporter());
      sdk = builder.build().getOpenTelemetrySdk();
    }

    GlobalOpenTelemetry.set(sdk);
    return sdk;
  }
}
