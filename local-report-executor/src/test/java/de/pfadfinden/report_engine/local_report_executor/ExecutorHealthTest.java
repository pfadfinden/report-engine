package de.pfadfinden.report_engine.local_report_executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExecutorHealthTest {

  @Test
  void healthyWhenProbeSucceeds() {
    assertTrue(new ExecutorHealth(() -> null, () -> 0).isHealthy());
  }

  @Test
  void unhealthyWhenProbeFails() {
    assertFalse(new ExecutorHealth(() -> new RuntimeException("no fonts"), () -> 0).isHealthy());
  }

  @Test
  void probeResultIsCachedUntilTheIntervalPasses() {
    AtomicInteger calls = new AtomicInteger();
    AtomicLong clock = new AtomicLong();
    ExecutorHealth health =
        new ExecutorHealth(
            () -> {
              calls.incrementAndGet();
              return null;
            },
            clock::get);

    health.isHealthy();
    health.isHealthy();
    assertTrue(calls.get() == 1);
    clock.addAndGet(ExecutorHealth.PROBE_INTERVAL_MS);
    health.isHealthy();
    assertTrue(calls.get() == 2);
  }

  @Test
  void poisoningErrorFlipsToUnhealthyStickily() {
    ExecutorHealth health = new ExecutorHealth(() -> null, () -> 0);
    health.recordFailure(new IllegalArgumentException("bad parameter"));
    assertTrue(health.isHealthy());

    health.recordFailure(
        new NoClassDefFoundError("Could not initialize class sun.font.FontManager"));
    assertFalse(health.isHealthy());
  }

  @Test
  void detectsPoisoningInTheCauseChain() {
    assertTrue(
        ExecutorHealth.isPoisoning(
            new RuntimeException(
                "fill failed", new InternalError("Fontconfig head is null, check your fonts"))));
  }
}
