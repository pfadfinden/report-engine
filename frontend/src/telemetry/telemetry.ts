import { NodeSDK } from '@opentelemetry/sdk-node';
import { SimpleLogRecordProcessor } from '@opentelemetry/sdk-logs';
import { AggregationType, InstrumentType, type ViewOptions } from '@opentelemetry/sdk-metrics';
import { getNodeAutoInstrumentations } from '@opentelemetry/auto-instrumentations-node';
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
 * getNodeAutoInstrumentations() - the standard "auto-instrumentations" meta-package, the Node
 * equivalent of the OpenTelemetry Java agent's own auto-instrumentation - patches every
 * OTel-instrumentable module this app has installed, not just the three used to hand-pick
 * (http/express/undici) here before: instrumentation-http + instrumentation-express give every
 * incoming request its own span (when tracing is enabled), extracting whatever trace context the
 * caller sent (or starting a fresh trace if none - e.g. a direct browser request);
 * instrumentation-undici covers outgoing fetch() calls, since Node's global fetch is implemented
 * via undici, not the legacy http/https modules instrumentation-http patches - this is what makes
 * calls to local-report-executor/azure-report-executor (see HttpReportExecutionService)
 * automatically carry a traceparent header, which is what actually connects this frontend's trace
 * to the executor's rather than each starting its own. instrumentation-fs (a span per filesystem
 * call - noisy enough that this bundle excludes it by default) stays off; nothing here overrides
 * that. instrumentation-host-metrics is turned back on below - it's excluded by default too
 * (metrics-only, no spans, so "noisy" doesn't apply the same way), but it's what actually supplies
 * process.cpu.utilization/process.memory.usage - the Node equivalent of the JVM CPU/memory metrics
 * local-report-executor's OpenTelemetry Java agent provides - which nothing else in the bundle
 * does; instrumentation-runtime-node (on by default) only covers the event loop/V8 heap/GC side.
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

  // NodeSDK's own default resource detectors (envDetector/processDetector/hostDetector) don't
  // include one for service.instance.id, unlike local-report-executor/azure-report-executor's
  // Java SDK, which always sets it - so this service's OTLP resource has no instance identity,
  // which silently breaks any dashboard/query that (like otel-lgtm's own bundled RED Metrics
  // dashboards) filters or joins on the "instance" label: a PromQL matcher on a label a series
  // doesn't have at all (not just empty) excludes that series entirely, even from "match
  // everything" wildcards - verified empirically. 'all' pulls in every bundled detector
  // (container/host/os/process/service-instance-id/env), matching what an operator would get by
  // default from most other OTel SDKs; unconditional (not gated by endpointConfigured) since it's
  // about identity, not on/off behavior. An operator-set value always wins over this default.
  if (process.env.OTEL_NODE_RESOURCE_DETECTORS === undefined) {
    process.env.OTEL_NODE_RESOURCE_DETECTORS = 'all';
  }

  // Matches local-report-executor's OTEL_EXPORTER_OTLP_METRICS_DEFAULT_HISTOGRAM_AGGREGATION=
  // base2_exponential_bucket_histogram (see Telemetry.java/docker-compose.observability.yml) -
  // there's no equivalent env var for the Node SDK, so it has to be a View instead. Without this,
  // every histogram (http.server.request.duration in particular) stays on the SDK's classic
  // explicit-bucket default, which needs its own separate dashboard/query shape (no `by (le)`
  // grouping, no _bucket/_count/_sum suffixes) from local-report-executor's native histograms -
  // see otel-lgtm's bundled "RED Metrics (native histogram)" vs "(classic histogram)" dashboards.
  const exponentialHistogramView: ViewOptions = {
    instrumentType: InstrumentType.HISTOGRAM,
    aggregation: { type: AggregationType.EXPONENTIAL_HISTOGRAM },
  };

  const sdk = new NodeSDK({
    serviceName: 'report-engine-frontend',
    views: [exponentialHistogramView],
    instrumentations: [
      getNodeAutoInstrumentations({
        '@opentelemetry/instrumentation-host-metrics': { enabled: true },
      }),
    ],
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
