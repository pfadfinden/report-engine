package de.pfadfinden.report_engine.local_report_executor;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;

/**
 * RED (rate/errors/duration) metrics for this service's own HTTP surface, recorded by hand around
 * every request (see Main's before/after hooks) rather than via the OpenTelemetry Java
 * instrumentation agent's Jetty/Javalin auto-instrumentation: this app already installs its own
 * GlobalOpenTelemetry (see Telemetry.java), and the agent claims that slot before main() runs, so
 * the two would collide. The single instrument's name/unit/attributes follow the stable HTTP server
 * semantic conventions
 * (https://opentelemetry.io/docs/specs/semconv/http/http-metrics/#metric-httpserverrequestduration)
 * so a RED dashboard built against that convention reads this service's traffic the same way it
 * would an agent-instrumented one - rate and errors both derive from this histogram's count, split
 * by http.response.status_code, so no separate counter is needed.
 */
final class HttpServerMetrics {

  private static final AttributeKey<String> HTTP_REQUEST_METHOD =
      AttributeKey.stringKey("http.request.method");
  private static final AttributeKey<String> HTTP_ROUTE = AttributeKey.stringKey("http.route");
  private static final AttributeKey<Long> HTTP_RESPONSE_STATUS_CODE =
      AttributeKey.longKey("http.response.status_code");

  private HttpServerMetrics() {}

  static void recordRequest(String method, String route, int statusCode, double durationSeconds) {
    GlobalOpenTelemetry.getMeter("report-engine")
        .histogramBuilder("http.server.request.duration")
        .setDescription("Duration of HTTP server requests")
        .setUnit("s")
        .build()
        .record(
            durationSeconds,
            Attributes.of(
                HTTP_REQUEST_METHOD,
                method,
                HTTP_ROUTE,
                route,
                HTTP_RESPONSE_STATUS_CODE,
                (long) statusCode));
  }
}
