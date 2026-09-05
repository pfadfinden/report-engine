import { ExportResult, ExportResultCode, hrTimeToTimeStamp } from '@opentelemetry/core';
import { LogRecordExporter, ReadableLogRecord } from '@opentelemetry/sdk-logs';
import { JsonLogsSerializer } from '@opentelemetry/otlp-transformer';

export type LogConsoleFormat = 'text' | 'json';

const EVENT_NAME_ATTRIBUTE = 'event.name';

/**
 * Prints one human-readable line per log record to stdout - the plain-text alternative to
 * OtlpJsonConsoleLogRecordExporter (see telemetry.ts, which switches between the two based on
 * LOG_CONSOLE_FORMAT). Not the SDK's own ConsoleLogRecordExporter: that one isn't guaranteed to
 * print one line per entry.
 */
export class ConsoleLogRecordExporter implements LogRecordExporter {
  export(logs: ReadableLogRecord[], resultCallback: (result: ExportResult) => void): void {
    for (const log of logs) {
      console.log(this.toLine(log));
    }
    resultCallback({ code: ExportResultCode.SUCCESS });
  }

  async shutdown(): Promise<void> {}

  async forceFlush(): Promise<void> {}

  private toLine(log: ReadableLogRecord): string {
    const parts = [hrTimeToTimeStamp(log.hrTime), log.severityText ?? 'INFO', String(log.body ?? '')];
    for (const [key, value] of Object.entries(log.attributes)) {
      if (key !== EVENT_NAME_ATTRIBUTE) {
        parts.push(`${key}=${String(value)}`);
      }
    }
    return parts.join(' ');
  }
}

/**
 * Prints one line of real OTLP/JSON per log record to stdout, for a production log aggregator
 * (Loki, CloudWatch, a Datadog/Fluent Bit agent tailing stdout) to parse - using
 * @opentelemetry/otlp-transformer's own JsonLogsSerializer (the same code
 * @opentelemetry/exporter-logs-otlp-http uses to build a real OTLP export request body) rather
 * than a hand-rolled schema, so the output is the standard shape any OTLP-aware tool already
 * understands.
 */
export class OtlpJsonConsoleLogRecordExporter implements LogRecordExporter {
  export(logs: ReadableLogRecord[], resultCallback: (result: ExportResult) => void): void {
    const bytes = JsonLogsSerializer.serializeRequest(logs);
    if (bytes) {
      console.log(new TextDecoder().decode(bytes));
    }
    resultCallback({ code: ExportResultCode.SUCCESS });
  }

  async shutdown(): Promise<void> {}

  async forceFlush(): Promise<void> {}
}

/** LOG_CONSOLE_FORMAT=json switches to structured JSON; anything else (including unset) is text. */
export function logConsoleFormatFromEnv(): LogConsoleFormat {
  return process.env.LOG_CONSOLE_FORMAT?.toLowerCase() === 'json' ? 'json' : 'text';
}
