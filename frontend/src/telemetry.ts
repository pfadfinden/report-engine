import { NodeSDK } from '@opentelemetry/sdk-node';
import { SimpleLogRecordProcessor } from '@opentelemetry/sdk-logs';
import { HttpInstrumentation } from '@opentelemetry/instrumentation-http';
import { ExpressInstrumentation } from '@opentelemetry/instrumentation-express';
import { UndiciInstrumentation } from '@opentelemetry/instrumentation-undici';
import {
  ConsoleLogRecordExporter,
  OtlpJsonConsoleLogRecordExporter,
  logConsoleFormatFromEnv,
} from './console-log-exporter';

/**
 * Registers the OpenTelemetry Node SDK - the frontend-side counterpart to
 * local-report-executor/azure-report-executor's Telemetry.init(). Reads the standard OTEL_* env
 * vars (https://opentelemetry.io/docs/languages/sdk-configuration/) so an operator can point this
 * at any OTLP-compatible collector without a code change.
 *
 * <p>If none of the OTLP endpoint env vars are set, traces and metrics are disabled outright (not
 * created at all, not even to console) rather than defaulting to NodeSDK's own "otlp against a
 * local collector" (which would silently fail to export) or a console dump: both are opt-in
 * debugging/analysis aids with their own overhead and noise, so they stay off until an operator
 * deliberately turns them on - by pointing OTEL_EXPORTER_OTLP_ENDPOINT/etc. at a real collector,
 * or by setting OTEL_TRACES_EXPORTER=console/OTEL_METRICS_EXPORTER=console themselves for local
 * visibility without one. Logs (the audit trail this was actually built for) are the exception:
 * they stay on by default via a custom console exporter (see console-log-exporter.ts) - one
 * human-readable line per entry by default, or one line of real OTLP/JSON per entry if
 * LOG_CONSOLE_FORMAT=json (see logConsoleFormatFromEnv). An explicit OTEL_TRACES_EXPORTER/etc.
 * always wins over these defaults.
 *
 * HttpInstrumentation + ExpressInstrumentation give every incoming request its own span (when
 * tracing is enabled), extracting whatever trace context the caller sent (or starting a fresh
 * trace if none - e.g. a direct browser request). UndiciInstrumentation covers outgoing fetch()
 * calls: Node's global fetch is implemented via undici, not the legacy http/https modules
 * HttpInstrumentation patches - this is what makes calls to
 * local-report-executor/azure-report-executor (see HttpReportExecutionService) automatically
 * carry a traceparent header, which is what actually connects this frontend's trace to the
 * executor's rather than each starting its own.
 *
 * Must be required before anything else: instrumentation patches modules (http, express, undici)
 * at require() time, so anything that imports them first would bypass the patch. See bin/www.ts.
 *
 * Returns a shutdown function that flushes and stops the SDK; the caller (bin/www.ts) is
 * responsible for invoking it as part of the process's own graceful-shutdown sequence rather than
 * this module racing it with its own signal handler.
 */
export function initTelemetry(): () => Promise<void> {
  const endpointConfigured =
    process.env.OTEL_EXPORTER_OTLP_ENDPOINT !== undefined ||
    process.env.OTEL_EXPORTER_OTLP_TRACES_ENDPOINT !== undefined ||
    process.env.OTEL_EXPORTER_OTLP_METRICS_ENDPOINT !== undefined ||
    process.env.OTEL_EXPORTER_OTLP_LOGS_ENDPOINT !== undefined;

  if (!endpointConfigured) {
    if (process.env.OTEL_TRACES_EXPORTER === undefined) {
      process.env.OTEL_TRACES_EXPORTER = 'none';
    }
    if (process.env.OTEL_METRICS_EXPORTER === undefined) {
      process.env.OTEL_METRICS_EXPORTER = 'none';
    }
  }

  const sdk = new NodeSDK({
    serviceName: 'report-engine-frontend',
    instrumentations: [new HttpInstrumentation(), new ExpressInstrumentation(), new UndiciInstrumentation()],
    // Only overridden when there's no real destination configured - with one, logs fall through
    // to NodeSDK's own env-driven OTLP setup like every other signal (see class doc above).
    ...(endpointConfigured
      ? {}
      : {
          logRecordProcessors: [
            new SimpleLogRecordProcessor({
              exporter:
                logConsoleFormatFromEnv() === 'json'
                  ? new OtlpJsonConsoleLogRecordExporter()
                  : new ConsoleLogRecordExporter(),
            }),
          ],
        }),
  });
  sdk.start();

  return () =>
    sdk.shutdown().catch(() => {
      // Best-effort flush on shutdown; a failure here shouldn't block the process exiting.
    });
}
