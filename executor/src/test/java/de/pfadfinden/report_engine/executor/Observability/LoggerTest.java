package de.pfadfinden.report_engine.executor.Observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * LOG_LEVEL itself is read from the real environment, which JUnit can't override per-test - these
 * tests exercise Logger.event()/error() (always emitted, matching the default "info" floor) and
 * rely on that default rather than parameterizing LOG_LEVEL. debug()/warn()'s own filtering logic
 * is identical (same emit() method, different Severity), so this still covers the mechanism.
 */
class LoggerTest {

  private InMemoryLogRecordExporter logExporter;

  @BeforeEach
  void setUp() {
    GlobalOpenTelemetry.resetForTest();
    logExporter = InMemoryLogRecordExporter.create();
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder()
            .setLoggerProvider(
                SdkLoggerProvider.builder()
                    .addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter))
                    .build())
            .build();
    GlobalOpenTelemetry.set(sdk);
  }

  @AfterEach
  void tearDown() {
    GlobalOpenTelemetry.resetForTest();
  }

  @Test
  void eventEmitsAnInfoRecordWithEventNameAttribute() {
    Logger.event(
        "report.trigger.requested",
        Attributes.of(io.opentelemetry.api.common.AttributeKey.stringKey("report.id"), "report-1"));

    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    assertEquals(1, logs.size());
    LogRecordData log = logs.get(0);
    assertEquals(Severity.INFO, log.getSeverity());
    assertEquals("report.trigger.requested", log.getBodyValue().asString());
    assertEquals(
        "report.trigger.requested",
        log.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("event.name")));
    assertEquals(
        "report-1",
        log.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("report.id")));
  }

  @Test
  void errorEmitsAnErrorRecordWithExceptionAttributes() {
    Logger.error("report.execution.failed", Attributes.empty(), new IllegalStateException("boom"));

    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    assertEquals(1, logs.size());
    LogRecordData log = logs.get(0);
    assertEquals(Severity.ERROR, log.getSeverity());
    assertTrue(
        log.getAttributes()
            .get(io.opentelemetry.api.common.AttributeKey.stringKey("error.type"))
            .endsWith("IllegalStateException"));
    assertEquals(
        "boom",
        log.getAttributes()
            .get(io.opentelemetry.api.common.AttributeKey.stringKey("error.message")));
  }

  @Test
  void errorRedactsJdbcCredentialsFromTheExceptionMessage() {
    Logger.error(
        "report.execution.failed",
        Attributes.empty(),
        new RuntimeException(
            "Connection to jdbc:postgresql://host:5432/db?user=hitobito&password=hitobito failed"));

    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    assertEquals(1, logs.size());
    assertEquals(
        "Connection to jdbc:postgresql://host:5432/db?user=REDACTED&password=REDACTED failed",
        logs.get(0)
            .getAttributes()
            .get(io.opentelemetry.api.common.AttributeKey.stringKey("error.message")));
  }
}
