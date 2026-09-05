package de.pfadfinden.report_engine.executor.Observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;

/**
 * Structured events - both audit events (report trigger/execution/download, at INFO/ERROR) and
 * operational/debugging ones (cache hits, retries, connection issues, at DEBUG/WARN) - emitted
 * directly through the OTel Logs Bridge API, the same signal pipeline as traces/metrics, exported
 * wherever azure-report-executor/local-report-executor's Telemetry bootstrap sends logs. Not cached
 * in a static field: GlobalOpenTelemetry's logs bridge is fetched fresh on each call so this works
 * correctly regardless of whether the SDK has been registered yet.
 *
 * <p>LOG_LEVEL (debug|info|warn|error, default info) sets the minimum severity actually emitted -
 * DEBUG-level operational detail stays quiet by default so it doesn't crowd out the audit trail,
 * and is available on demand for troubleshooting without a code change.
 */
public final class Logger {

  private static final String SCOPE = "report-engine";
  private static final AttributeKey<String> EVENT_NAME = AttributeKey.stringKey("event.name");
  private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
  private static final AttributeKey<String> ERROR_MESSAGE = AttributeKey.stringKey("error.message");

  private Logger() {}

  /** Operational/debugging detail (e.g. a cache hit, a retry) - quiet unless LOG_LEVEL=debug. */
  public static void debug(String eventName, Attributes attributes) {
    emit(Severity.DEBUG, eventName, attributes, null);
  }

  /** A business-level audit event (e.g. a report was triggered/downloaded). */
  public static void event(String eventName, Attributes attributes) {
    emit(Severity.INFO, eventName, attributes, null);
  }

  /** Something recoverable went wrong or looks off (e.g. a fallback kicked in). */
  public static void warn(String eventName, Attributes attributes) {
    emit(Severity.WARN, eventName, attributes, null);
  }

  /** A business-level audit event that failed. */
  public static void error(String eventName, Attributes attributes, Throwable error) {
    emit(Severity.ERROR, eventName, attributes, error);
  }

  private static void emit(
      Severity severity, String eventName, Attributes attributes, Throwable error) {
    if (severity.getSeverityNumber() < minSeverity().getSeverityNumber()) {
      return;
    }
    LogRecordBuilder builder =
        otelLogger()
            .logRecordBuilder()
            .setSeverity(severity)
            .setBody(eventName)
            .setAttribute(EVENT_NAME, eventName)
            .setAllAttributes(attributes);
    if (error != null) {
      builder
          .setAttribute(ERROR_TYPE, error.getClass().getName())
          .setAttribute(ERROR_MESSAGE, String.valueOf(error.getMessage()));
    }
    builder.emit();
  }

  private static Severity minSeverity() {
    String level = System.getenv("LOG_LEVEL");
    if (level == null) {
      return Severity.INFO;
    }
    return switch (level.toUpperCase()) {
      case "DEBUG" -> Severity.DEBUG;
      case "WARN" -> Severity.WARN;
      case "ERROR" -> Severity.ERROR;
      default -> Severity.INFO;
    };
  }

  private static io.opentelemetry.api.logs.Logger otelLogger() {
    return GlobalOpenTelemetry.get().getLogsBridge().loggerBuilder(SCOPE).build();
  }
}
