package de.pfadfinden.report_engine.executor.Port;

import de.pfadfinden.report_engine.executor.Exceptions.FailedToStoreReport;
import java.io.OutputStream;

/**
 * Destination a filled report is written to, keyed by {@link ReportTaskConfiguration#outputName}.
 * The caller closes the returned stream.
 */
public interface ReportOutputStore {

  public OutputStream open(String outputName) throws FailedToStoreReport;
}
