package de.pfadfinden.report_engine.executor.Observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.HashMap;
import java.util.Map;

/**
 * W3C trace context extract/inject against a plain Map&lt;String,String&gt; carrier, so the same
 * two methods work uniformly against Javalin's header map, Azure's HttpRequestMessage headers, and
 * a field embedded in the (otherwise opaque, header-less) queue message JSON payload - whichever
 * hop needs to connect its spans to the caller's trace instead of starting a new one.
 */
public final class TraceContextPropagation {

  private static final TextMapGetter<Map<String, String>> GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
          return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
          return carrier == null ? null : carrier.get(key);
        }
      };

  private static final TextMapSetter<Map<String, String>> SETTER = Map::put;

  private TraceContextPropagation() {}

  /** Extracts the trace context carried by the given headers, if any - otherwise a no-op. */
  public static Context extract(Map<String, String> carrier) {
    if (carrier == null) {
      return Context.current();
    }
    return GlobalOpenTelemetry.get()
        .getPropagators()
        .getTextMapPropagator()
        .extract(Context.current(), carrier, GETTER);
  }

  /**
   * The currently active trace context, as headers to carry across a boundary with no headers of
   * its own (e.g. a queue message).
   */
  public static Map<String, String> injectCurrent() {
    Map<String, String> carrier = new HashMap<>();
    GlobalOpenTelemetry.get()
        .getPropagators()
        .getTextMapPropagator()
        .inject(Context.current(), carrier, SETTER);
    return carrier;
  }
}
