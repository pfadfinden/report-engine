package de.pfadfinden.reports_engine.executor.Port;

import java.io.OutputStream;

import de.pfadfinden.reports_engine.executor.Exceptions.FailedToStoreReport;

/**
 * Destination a filled report is written to, keyed by
 * {@link ReportTaskConfiguration#outputName}. The caller closes the returned
 * stream.
 */
public interface ReportOutputStore {

    public OutputStream open(String outputName) throws FailedToStoreReport;
}
