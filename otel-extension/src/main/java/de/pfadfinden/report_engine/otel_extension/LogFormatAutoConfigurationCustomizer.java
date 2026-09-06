package de.pfadfinden.report_engine.otel_extension;

import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingLogRecordExporter;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import java.util.Map;

/**
 * Keeps the "off by default, console logs always on" behavior described in TELEMETRY.md working
 * identically across every way this ends up loaded - it's the same
 * io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider SPI, just discovered
 * from wherever a given caller's own AutoConfiguredOpenTelemetrySdk.builder().build() call happens:
 * local-report-executor's Telemetry.java when no agent is attached, an agent extension jar
 * (-Dotel.javaagent.extensions, see that module's Dockerfile) when it is, and
 * azure-report-executor's Telemetry.java, which never runs the agent at all. One implementation, so
 * none of these can drift into different default behavior.
 *
 * <p>Without this, autoconfigure (agent or bare builder call alike) defaults every signal's
 * exporter to "otlp" against localhost:4317 - which would silently fail to export instead of doing
 * nothing - and logs would lose the clean single-line-per-event format this app's audit trail
 * depends on.
 */
public class LogFormatAutoConfigurationCustomizer implements AutoConfigurationCustomizerProvider {

  /**
   * Escape hatch for a caller with its own, non-OTLP notion of "a real backend is configured" -
   * azure-report-executor's Telemetry.java sets this (before building its SDK) when
   * APPLICATIONINSIGHTS_CONNECTION_STRING is present, since Azure Monitor is a real backend this
   * class has no business knowing the name of. Deliberately a system property, not an env var: it's
   * meant to be set from Java code reacting to whatever deployment-specific signal a given executor
   * has, not something an operator sets directly.
   */
  public static final String BACKEND_CONFIGURED_PROPERTY = "otel.extension.backend-configured";

  @Override
  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    if (backendConfigured()) {
      return;
    }
    autoConfiguration.addPropertiesSupplier(
        () ->
            Map.of(
                "otel.traces.exporter", "none",
                "otel.metrics.exporter", "none",
                // A real exporter still has to be selected here so there's something for the
                // customizer below to replace - "logging" itself is discarded, only its plumbing
                // (a SimpleLogRecordProcessor) is reused.
                "otel.logs.exporter", "logging"));
    autoConfiguration.addLogRecordExporterCustomizer(
        (exporter, config) ->
            "json".equalsIgnoreCase(System.getenv("LOG_CONSOLE_FORMAT"))
                ? OtlpJsonLoggingLogRecordExporter.create()
                : new ConsoleLogRecordExporter());
  }

  private static boolean backendConfigured() {
    return System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") != null
        || System.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT") != null
        || System.getenv("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT") != null
        || System.getenv("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT") != null
        || Boolean.getBoolean(BACKEND_CONFIGURED_PROPERTY);
  }
}
