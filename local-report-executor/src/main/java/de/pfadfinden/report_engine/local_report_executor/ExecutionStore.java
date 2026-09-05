package de.pfadfinden.report_engine.local_report_executor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local table of execution state, keyed by the client-supplied executionId. See
 * ExecutionState for the durability caveat.
 */
public class ExecutionStore {

  private final Map<String, ExecutionState> executions = new ConcurrentHashMap<>();

  public void createPending(String executionId, String reportId) {
    ExecutionState state = new ExecutionState();
    state.reportId = reportId;
    executions.put(executionId, state);
  }

  public ExecutionState get(String executionId) {
    return executions.get(executionId);
  }
}
