# Telemetry

This project uses [OpenTelemetry](https://opentelemetry.io) across all runtime components for an **audit log**, **operational logs** and **usage statistics over time**. **Distributed tracing** is a third, separate, opt-in signal for following one report generation across process/service boundaries - not needed for either goal above, so it stays off by default.

| Signal | Default | To enable |
| --- | --- | --- |
| Logs | **On** - console, plain text, one line/event | `LOG_CONSOLE_FORMAT=json` for structured output, or a real backend below |
| Metrics (usage stats) | **Off** - not created at all | An OTLP endpoint, or `OTEL_METRICS_EXPORTER=console` for local visibility |
| Traces (distributed tracing) | **Off** - not created at all | An OTLP endpoint, or `OTEL_TRACES_EXPORTER=console` for local visibility |

## Table of contents

- [Configuration reference](#configuration-reference)
  - [Env vars (all components)](#env-vars-all-components)
  - [`local-report-executor`](#service-local-report-executor)
  - [`azure-report-executor`](#service-azure-report-executor)
  - [`frontend`](#service-report-engine-frontend)
- [What gets recorded](#what-gets-recorded)
  - [Log events](#log-events)
  - [Metrics](#metrics)
  - [Tracing](#tracing)

## Configuration reference

### Env vars (all components)

| Variable | Effect |
| --- | --- |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Base OTLP endpoint for all three signals. Setting this (or any of the three below) flips traces/metrics from disabled to OTLP export, alongside logs. |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` / `..._METRICS_ENDPOINT` / `..._LOGS_ENDPOINT` | Per-signal override. |
| `OTEL_TRACES_EXPORTER` | `none` (default, no endpoint) \| `console`/`logging` \| `otlp` (default once an endpoint is set) |
| `OTEL_METRICS_EXPORTER` | Same values/logic, for metrics. |
| `OTEL_LOGS_EXPORTER` | Not read directly - see each service below; logs default to console regardless of trace/metric endpoint config. |
| `LOG_CONSOLE_FORMAT` | Console log format only (irrelevant once shipped via OTLP). Unset/anything but `json` (default): one plain-text line/record, e.g. `2026-09-05T08:31:24.665Z ERROR report.trigger.failed error.message=...`. `json`: one line of real [OTLP/JSON](https://opentelemetry.io/docs/specs/otlp/) per record, for a log aggregator (Loki, CloudWatch, Fluent Bit/Datadog) tailing stdout in production. |
| `LOG_LEVEL` | `debug` \| `info` (default) \| `warn` \| `error` - minimum severity emitted. [Audit events](#audit-log-events) are always INFO/ERROR, so unaffected; this only gates [operational/debugging events](#operationaldebugging-log-events) (DEBUG/WARN), which stay quiet until you need cache/retry/connection-level detail. |

### Service: `local-report-executor`

No component-specific env vars.

### Service: `azure-report-executor`

| Variable | Effect |
| --- | --- |
| `APPLICATIONINSIGHTS_CONNECTION_STRING` | Routes all three signals through [Azure Monitor's OTel distro](https://learn.microsoft.com/en-us/java/api/overview/azure/monitor-opentelemetry-autoconfigure-readme) instead of the generic OTLP/console defaults - the "enable telemetry" switch for Azure, equivalent to an OTLP endpoint. |

Two gaps, documented in code where they matter: the download endpoint only ever issues a SAS URL
(the actual bytes are fetched straight from Blob Storage, invisible to this Java code - its
`report.download.link_issued` event covers issuance only), and trace context has to be carried
explicitly across the `report-tasks` queue hop via a `traceContext` field on
`ReportExecutionMessage` (Azure Storage Queues have no headers).

### Service: `report-engine-frontend`

No component-specific env vars.

## What gets recorded

### Log events

| Event | Severity | Emitted where | Attributes |
| --- | --- | --- | --- |
| `report.trigger.requested` | INFO | Both executors, on trigger | `report.id`, `execution.id`, `output.format`, `group.id` (best-effort, if present) |
| `report.trigger.failed` | ERROR | Azure, if enqueueing/status writes fail | `report.id`, `execution.id`, `error.type`, `error.message` |
| `report.execution.completed` | INFO | Both, on a successful fill | `report.id`, `execution.id`, `status=success`, `duration.ms`, `output.size_bytes` |
| `report.execution.failed` | ERROR | Both, on any load/coerce/fill/export failure | `report.id`, `execution.id`, `status=failure`, `duration.ms`, `error.type`, `error.message` |
| `report.download` | INFO | Local, when bytes are served from `/files/{id}` | `report.id`, `execution.id`, `output.size_bytes` |
| `report.download.link_issued` | INFO | Azure, when a SAS URL is issued (not the download itself) | `execution.id` |
| `reports.cache.hit` | DEBUG | Azure `RemoteZipReportLoader` | `cache.downloaded_at` |
| `reports.cache.refresh` | DEBUG | Azure `RemoteZipReportLoader` | `reports.source_url` |
| `reports.cache.refreshed` | DEBUG | Azure `RemoteZipReportLoader` | `download.size_bytes`, `duration.ms` |
| `reports.download.failed` | WARN | Azure `RemoteZipReportLoader` | `reports.source_url`, `http.status_code` |
| `reports.cache.stale_file_not_removed` | WARN | Azure `RemoteZipReportLoader` | `path` |
| `metadata.cache.hit` | DEBUG | Frontend `RemoteMetadataLoaderService` | `cache.age_ms` |
| `metadata.cache.refresh` | DEBUG | Frontend `RemoteMetadataLoaderService` | `metadata.source_url` |
| `metadata.cache.refreshed` | DEBUG | Frontend `RemoteMetadataLoaderService` | `download.size_bytes`, `duration.ms` |
| `metadata.download.failed` | WARN | Frontend `RemoteMetadataLoaderService` | `metadata.source_url`, `http.status_code` |
| `auth.login` | INFO | Frontend `auth-router`, on successful login | `principal.id`, `principal.name` |
| `auth.logout` | INFO | Frontend `auth-router`, on logout | `principal.id`, `principal.name` (if the session still had one) |
| `report.trigger` | INFO | Frontend `report-execution` router, on a successful `POST /generate` | `principal.id`, `report.id`, `execution.id`, `group.id` |
| `authz.denied` | WARN | Frontend `report-execution` router, on a 403 (group/report not authorized) | `principal.id`, `reason` (`group`\|`report`), `group.id`, `report.id` (if applicable) |
| `http.request.failed` | ERROR | Frontend Express error handler, on any unhandled request error | `http.status_code`, `http.method`, `http.path`, `error.type`, `error.message` |
| `http.access` | INFO | Frontend, one per request (morgan, piped through the OTel logger instead of straight to stdout) | `message` (the formatted access line) |
| `healthz.check_failed` | ERROR | Frontend `GET /healthz`, per failed dependency check | `check`, `error.type`, `error.message` |
| `server.listening` | INFO | Frontend `bin/www`, once the HTTP server is accepting connections | `bind` |
| `server.listen.failed` | ERROR | Frontend `bin/www`, on `EACCES`/`EADDRINUSE` at startup | `bind`, `error.type`, `error.message` |
| `server.shutdown.started` | INFO | Frontend `bin/www`, on `SIGTERM`/`SIGINT` | `signal` |
| `server.shutdown.timed_out` | ERROR | Frontend `bin/www`, if graceful shutdown exceeds 10s | `error.type`, `error.message` |
| `server.shutdown.failed` | ERROR | Frontend `bin/www`, if `server.close` errors | `error.type`, `error.message` |
| `server.startup.failed` | ERROR | Frontend `bin/www`, if `main()` rejects | `error.type`, `error.message` |

### Metrics

| Instrument | Type | Unit | Attributes |
| --- | --- | --- | --- |
| `report.executions.triggered` | Counter | - | `report.id` |
| `report.executions.completed` | Counter | - | `report.id`, `status` |
| `report.execution.duration` | Histogram | `ms` | `report.id`, `status` |
| `report.output.size` | Histogram | `By` | `report.id` (success only) |
| `report.downloads` | Counter | - | `report.id` (local only) |

### Tracing

| Span | Where | Notes |
| --- | --- | --- |
| `report.trigger` | Both | Root when the frontend sent no `traceparent` |
| `report.execution` | Both | Wraps load→coerce→fill→export→store |
| `report.fill` | `executor` (shared) | Nested in `report.execution` - just the JasperReports step |
| `report.status` | Both | Status-poll endpoint |
| `report.download` | Local | The byte-serving `/files/{id}` request |
| `report.download.url_issued` | Local | `/executions/{id}/download` - hands back the signed URL |
| `report.download.link_issued` | Azure | Equivalent - SAS issuance, not the download itself |
