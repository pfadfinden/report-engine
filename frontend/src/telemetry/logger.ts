import { logs, SeverityNumber } from '@opentelemetry/api-logs';

const SCOPE = 'report-engine';
const EVENT_NAME_ATTRIBUTE = 'event.name';

const LEVELS: Record<string, SeverityNumber> = {
  debug: SeverityNumber.DEBUG,
  info: SeverityNumber.INFO,
  warn: SeverityNumber.WARN,
  error: SeverityNumber.ERROR,
};

type AuditAttributes = Record<string, string | number | boolean>;

/**
 * Structured events - both audit events (report trigger, at INFO/ERROR) and operational/debugging
 * ones (cache hits, download retries, at DEBUG/WARN) - emitted directly through the OTel Logs
 * API, the same signal pipeline telemetry.ts wires up. The Java-side counterpart is
 * executor's Observability/Logger.java - see that class for the full rationale (same emission
 * shape, same LOG_LEVEL-gated severity filtering) kept in sync here.
 *
 * LOG_LEVEL (debug|info|warn|error, default info) sets the minimum severity actually emitted -
 * DEBUG-level operational detail stays quiet by default so it doesn't crowd out the audit trail,
 * and is available on demand for troubleshooting without a code change.
 */
function minSeverity(): SeverityNumber {
  const level = process.env.LOG_LEVEL?.toLowerCase();
  return (level ? LEVELS[level] : undefined) ?? SeverityNumber.INFO;
}

function emit(
  severityNumber: SeverityNumber,
  severityText: string,
  eventName: string,
  attributes: AuditAttributes,
  error?: unknown,
): void {
  if (severityNumber < minSeverity()) {
    return;
  }
  const allAttributes: AuditAttributes = { ...attributes, [EVENT_NAME_ATTRIBUTE]: eventName };
  if (error !== undefined) {
    allAttributes['error.type'] = error instanceof Error ? error.constructor.name : typeof error;
    allAttributes['error.message'] = error instanceof Error ? error.message : String(error);
  }
  logs.getLogger(SCOPE).emit({ severityNumber, severityText, body: eventName, attributes: allAttributes });
}

/** Operational/debugging detail (e.g. a cache hit) - quiet unless LOG_LEVEL=debug. */
export function debug(eventName: string, attributes: AuditAttributes = {}): void {
  emit(SeverityNumber.DEBUG, 'DEBUG', eventName, attributes);
}

/** A business-level audit event. */
export function event(eventName: string, attributes: AuditAttributes = {}): void {
  emit(SeverityNumber.INFO, 'INFO', eventName, attributes);
}

/** Something recoverable went wrong or looks off (e.g. a fallback kicked in). */
export function warn(eventName: string, attributes: AuditAttributes = {}): void {
  emit(SeverityNumber.WARN, 'WARN', eventName, attributes);
}

/** A business-level audit event that failed. */
export function error(eventName: string, attributes: AuditAttributes, err: unknown): void {
  emit(SeverityNumber.ERROR, 'ERROR', eventName, attributes, err);
}
