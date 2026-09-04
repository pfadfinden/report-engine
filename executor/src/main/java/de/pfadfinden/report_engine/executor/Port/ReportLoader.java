package de.pfadfinden.report_engine.executor.Port;

import de.pfadfinden.report_engine.executor.Exceptions.FailedToLoadReport;

public interface ReportLoader {

  public ReportDefinition load(String reportName) throws FailedToLoadReport;
}
