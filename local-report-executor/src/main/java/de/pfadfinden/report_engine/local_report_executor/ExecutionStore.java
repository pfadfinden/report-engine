package de.pfadfinden.report_engine.local_report_executor;

import de.pfadfinden.report_engine.executor.Port.ExecutionStatus;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local table of execution state, keyed by the client-supplied executionId. See
 * ExecutionState for the durability caveat. Bounded: RetentionCleaner removes old entries, and
 * {@code maxEntries} caps what can accumulate between sweeps.
 */
public class ExecutionStore {

  /** Thrown when the cap is reached and every entry is still running, so nothing can be evicted. */
  public static class StoreFullException extends RuntimeException {
    public StoreFullException(int maxEntries) {
      super("Execution store is full (" + maxEntries + " running executions)");
    }
  }

  private final Map<String, ExecutionState> executions = new ConcurrentHashMap<>();
  private final int maxEntries;

  public ExecutionStore() {
    this(Integer.MAX_VALUE);
  }

  public ExecutionStore(int maxEntries) {
    this.maxEntries = maxEntries;
  }

  public synchronized void createPending(String executionId, String reportId) {
    if (!executions.containsKey(executionId) && executions.size() >= maxEntries) {
      evictOldestFinished();
    }
    ExecutionState state = new ExecutionState();
    state.reportId = reportId;
    executions.put(executionId, state);
  }

  public ExecutionState get(String executionId) {
    return executions.get(executionId);
  }

  /** Removes the entry only if it is still the given state (never a re-created one). */
  public boolean remove(String executionId, ExecutionState expected) {
    return executions.remove(executionId, expected);
  }

  /** Live view for cleanup; entries may change while iterating. */
  public Map<String, ExecutionState> entries() {
    return executions;
  }

  private void evictOldestFinished() {
    executions.entrySet().stream()
        .filter(e -> e.getValue().status != ExecutionStatus.PENDING)
        .min(Comparator.comparing(e -> e.getValue().createdAt))
        .ifPresentOrElse(
            e -> executions.remove(e.getKey(), e.getValue()),
            () -> {
              throw new StoreFullException(maxEntries);
            });
  }
}
