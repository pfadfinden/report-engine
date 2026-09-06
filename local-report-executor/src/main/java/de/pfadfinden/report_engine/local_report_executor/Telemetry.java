package de.pfadfinden.report_engine.local_report_executor;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

/**
 * Registers the OpenTelemetry SDK as the process-wide GlobalOpenTelemetry, read by executor's
 * FillReportService and this module's own instrumentation (Main's request-metering hooks in
 * particular).
 *
 * <p>Two ways this ends up wired, per
 * https://opentelemetry.io/docs/languages/java/instrumentation/#manual-instrumentation: with the
 * OpenTelemetry Java instrumentation agent attached (-javaagent, see Dockerfile), the agent has
 * already built and registered the global SDK - including Jetty's http.server.* metrics/spans -
 * before main() runs, so this just hooks Logback's appender into whatever the agent installed.
 * Without the agent (tests, or a bare `java -jar`), this builds the SDK itself via the same
 * autoconfigure mechanism the agent uses internally, so it needs to do that wiring itself too.
 *
 * <p>Either way, the "off by default, clean console logs otherwise" behavior described in
 * TELEMETRY.md comes from otel-extension's LogFormatAutoConfigurationCustomizer, not from code here
 * - it's discovered via the standard AutoConfigurationCustomizerProvider SPI, either off this app's
 * own classpath (the no-agent path below) or from the agent's -Dotel.javaagent.extensions jar (see
 * Dockerfile), so the two paths can't drift apart into different default behavior.
 */
public final class Telemetry {

  private Telemetry() {}

  public static void init() {
    if (agentPresent()) {
      OpenTelemetryAppender.install(GlobalOpenTelemetry.get());
      return;
    }
    OpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder().build().getOpenTelemetrySdk();
    GlobalOpenTelemetry.set(sdk);
    OpenTelemetryAppender.install(sdk);
  }

  /**
   * Verified empirically (there's no documented system property for this - the agent doesn't set
   * "otel.javaagent.version" or similar, and its own GlobalOpenTelemetry.set() interception logs a
   * WARN and silently no-ops rather than throwing, so that alone can't be used to detect it
   * either): the agent injects its bootstrap classes onto the bootstrap classloader (visible from
   * anywhere, including here), so a class from there being loadable is equivalent to the agent
   * being attached. Deliberately not GlobalOpenTelemetry.get(): calling that before anyone has
   * called .set() permanently locks in the no-op implementation (get() implicitly calls set(noop)
   * the first time), which would make the manual buildSdk()/set() path below throw "already been
   * called" instead of ever getting to register the real SDK.
   */
  private static boolean agentPresent() {
    try {
      Class.forName("io.opentelemetry.javaagent.bootstrap.AgentInitializer", false, null);
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }
}
