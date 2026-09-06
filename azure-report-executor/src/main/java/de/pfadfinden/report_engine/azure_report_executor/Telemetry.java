package de.pfadfinden.report_engine.azure_report_executor;

import com.azure.monitor.opentelemetry.autoconfigure.AzureMonitorAutoConfigure;
import de.pfadfinden.report_engine.otel_extension.LogFormatAutoConfigurationCustomizer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
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
 *
 * <p>The "off by default, clean console logs otherwise" behavior (including LOG_CONSOLE_FORMAT)
 * comes from otel-extension's LogFormatAutoConfigurationCustomizer, not from code here - it's
 * discovered automatically via the standard AutoConfigurationCustomizerProvider SPI off this
 * module's own classpath, the same one local-report-executor uses (with or without its agent), so
 * the "no real backend configured" default can't drift into different behavior between the two
 * executors. That customizer has no reason to know Azure Monitor's connection-string env var
 * exists, so when it's present, this class tells the customizer to stand down via its generic
 * escape hatch (BACKEND_CONFIGURED_PROPERTY) instead.
 */
public final class Telemetry {

  private static final OpenTelemetry INSTANCE = init();

  private Telemetry() {}

  public static OpenTelemetry get() {
    return INSTANCE;
  }

  private static OpenTelemetry init() {
    String connectionString = System.getenv("APPLICATIONINSIGHTS_CONNECTION_STRING");
    boolean azureMonitorConfigured = connectionString != null && !connectionString.isBlank();

    AutoConfiguredOpenTelemetrySdkBuilder builder =
        AutoConfiguredOpenTelemetrySdk.builder()
            .addPropertiesSupplier(() -> Map.of("otel.service.name", "azure-report-executor"));

    if (azureMonitorConfigured) {
      System.setProperty(LogFormatAutoConfigurationCustomizer.BACKEND_CONFIGURED_PROPERTY, "true");
      AzureMonitorAutoConfigure.customize(builder, connectionString);
    }

    OpenTelemetrySdk sdk = builder.build().getOpenTelemetrySdk();
    GlobalOpenTelemetry.set(sdk);
    return sdk;
  }
}
