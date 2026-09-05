package de.pfadfinden.report_engine.local_report_executor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.io.PrintStream;
import java.time.Instant;
import java.util.Collection;

/**
 * Prints one human-readable line per log record to stdout - the plain-text alternative to
 * OtlpJsonLoggingLogRecordExporter (see Telemetry.init(), which switches between the two based on
 * LOG_CONSOLE_FORMAT). Not the SDK's own built-in "logging" LogRecordExporter: that one reads
 * getTimestampEpochNanos() directly, which stays 0 (and so prints as 1970-01-01) whenever a caller
 * (like Logger) never calls LogRecordBuilder.setTimestamp() itself - this falls back to
 * getObservedTimestampEpochNanos(), which the SDK reliably auto-populates instead.
 */
public class ConsoleLogRecordExporter implements LogRecordExporter {

  private static final AttributeKey<String> EVENT_NAME = AttributeKey.stringKey("event.name");

  private final PrintStream out;

  public ConsoleLogRecordExporter() {
    this(System.out);
  }

  ConsoleLogRecordExporter(PrintStream out) {
    this.out = out;
  }

  @Override
  public CompletableResultCode export(Collection<LogRecordData> logs) {
    for (LogRecordData log : logs) {
      out.println(toLine(log));
    }
    return CompletableResultCode.ofSuccess();
  }

  private String toLine(LogRecordData log) {
    StringBuilder line = new StringBuilder();
    line.append(timestamp(log)).append(' ').append(log.getSeverity()).append(' ').append(body(log));
    log.getAttributes()
        .forEach(
            (key, value) -> {
              if (!key.equals(EVENT_NAME)) {
                line.append(' ').append(key.getKey()).append('=').append(value);
              }
            });
    return line.toString();
  }

  private Instant timestamp(LogRecordData log) {
    long nanos = log.getTimestampEpochNanos();
    return Instant.ofEpochSecond(0, nanos != 0 ? nanos : log.getObservedTimestampEpochNanos());
  }

  private String body(LogRecordData log) {
    return log.getBodyValue() != null ? log.getBodyValue().asString() : "";
  }

  @Override
  public CompletableResultCode flush() {
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    return CompletableResultCode.ofSuccess();
  }
}
