package de.pfadfinden.report_engine.local_report_executor;

import de.pfadfinden.report_engine.executor.Observability.Logger;
import io.opentelemetry.api.common.Attributes;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Whether this process can still fill reports at all. A bare "the HTTP server answers" liveness
 * check can't tell: once java.desktop's font manager fails to initialize (no fontconfig/fonts) its
 * static state is poisoned for the life of the process, and every later fill fails - yet /healthz
 * would keep answering 200. Two signals flip this to unhealthy, so the container healthcheck (and
 * the frontend's own /healthz) notice:
 *
 * <ul>
 *   <li>a fill failed with an Error that indicates poisoned static state (see {@link
 *       #recordFailure}) - sticky, only a restart clears it;
 *   <li>a cheap font-metrics probe fails - run once and then at most every {@value
 *       #PROBE_INTERVAL_MS} ms, so a frequent healthcheck stays cheap.
 * </ul>
 */
public class ExecutorHealth {

  static final long PROBE_INTERVAL_MS = 30_000;

  private final Supplier<Throwable> probe;
  private final LongSupplier clockMs;

  private volatile boolean poisoned;
  private volatile boolean lastProbeOk = true;
  private volatile long lastProbeAtMs = Long.MIN_VALUE;

  public ExecutorHealth() {
    this(ExecutorHealth::fontProbe, System::currentTimeMillis);
  }

  /**
   * @param probe returns null when healthy, otherwise what went wrong.
   */
  ExecutorHealth(Supplier<Throwable> probe, LongSupplier clockMs) {
    this.probe = probe;
    this.clockMs = clockMs;
  }

  /** Always healthy: for tests that don't care about health. */
  static ExecutorHealth alwaysHealthy() {
    return new ExecutorHealth(() -> null, () -> 0);
  }

  /** Exactly what a Jasper fill does first: ask AWT for text metrics. */
  private static Throwable fontProbe() {
    try {
      new Font(Font.DIALOG, Font.PLAIN, 12)
          .getStringBounds("x", new FontRenderContext(null, true, true));
      return null;
    } catch (Throwable t) {
      return t;
    }
  }

  /** Called for anything a fill threw; only errors that signal poisoned static state count. */
  public void recordFailure(Throwable t) {
    if (isPoisoning(t) && !poisoned) {
      poisoned = true;
      Logger.error("executor.health.poisoned", Attributes.empty(), t);
    }
  }

  static boolean isPoisoning(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof NoClassDefFoundError || c instanceof ExceptionInInitializerError) {
        return true;
      }
      String message = c.getMessage();
      if (message != null && message.contains("Fontconfig")) {
        return true;
      }
      if (c.getCause() == c) {
        break;
      }
    }
    return false;
  }

  public boolean isHealthy() {
    if (poisoned) {
      return false;
    }
    long now = clockMs.getAsLong();
    if (lastProbeAtMs == Long.MIN_VALUE || now - lastProbeAtMs >= PROBE_INTERVAL_MS) {
      Throwable failure = probe.get();
      lastProbeAtMs = now;
      lastProbeOk = failure == null;
      if (failure != null) {
        Logger.error("executor.health.probe_failed", Attributes.empty(), failure);
      }
    }
    return lastProbeOk;
  }
}
