package de.pfadfinden.reports_engine.local_report_executor;

import java.io.File;

import de.pfadfinden.reports_engine.executor.Port.ExecutionStatus;
import de.pfadfinden.reports_engine.executor.Port.OutputFormat;

/**
 * In-memory record of a single report execution, from trigger to completion.
 * State is process-local only and is lost on restart - acceptable for a
 * single-instance local/self-hosted executor.
 */
public class ExecutionState {

    public volatile ExecutionStatus status = ExecutionStatus.PENDING;
    public volatile File outputFile;
    public volatile String errorMessage;
    public volatile OutputFormat outputFormat;
}
