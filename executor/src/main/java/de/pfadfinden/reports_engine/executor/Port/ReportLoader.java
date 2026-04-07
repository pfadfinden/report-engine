package de.pfadfinden.reports_engine.executor.Port;

import de.pfadfinden.reports_engine.executor.Exceptions.FailedToLoadReport;

public interface ReportLoader {

    public ReportDefinition load(String reportName) throws FailedToLoadReport;
}
