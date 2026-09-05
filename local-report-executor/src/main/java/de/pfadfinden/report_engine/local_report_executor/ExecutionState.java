package de.pfadfinden.report_engine.local_report_executor;

import de.pfadfinden.report_engine.executor.Port.ExecutionStatus;
import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import java.io.File;

/**
 * In-memory record of a single report execution, from trigger to completion. State is process-local
 * only and is lost on restart - acceptable for a single-instance local/self-hosted executor.
 */
public class ExecutionState {

  public volatile ExecutionStatus status = ExecutionStatus.PENDING;
  public volatile File outputFile;
  public volatile String errorMessage;
  public volatile OutputFormat outputFormat;
  public volatile String reportId;
}
