import { context, propagation, Context } from '@opentelemetry/api';

/**
 * Captures the currently active trace context as plain headers, storable anywhere with no
 * OpenTelemetry API of its own - here, the session, keyed by executionId (see session.d.ts).
 *
 * Needed because trigger, each status poll, and the download are otherwise unrelated incoming
 * HTTP requests: each gets its own fresh trace from HttpInstrumentation, with nothing to link
 * them together the way a single request's own child spans naturally are.
 */
export function captureCurrentTraceContext(): Record<string, string> {
  const carrier: Record<string, string> = {};
  propagation.inject(context.active(), carrier);
  return carrier;
}

/**
 * Runs fn with the given previously-captured trace context restored as active, so any spans fn
 * creates (including, critically, the outgoing fetch() span instrumentation-undici creates for
 * calls to local-report-executor/azure-report-executor) become part of that original trace
 * instead of this request's own fresh one. Falls back to the current context if none was stored
 * (e.g. an executionId from before this feature existed, or session data that expired).
 */
export function withStoredTraceContext<T>(carrier: Record<string, string> | undefined, fn: () => T): T {
  const restored: Context = carrier ? propagation.extract(context.active(), carrier) : context.active();
  return context.with(restored, fn);
}
