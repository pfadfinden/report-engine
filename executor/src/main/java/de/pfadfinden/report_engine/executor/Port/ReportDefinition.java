package de.pfadfinden.report_engine.executor.Port;

import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.JasperReportsContext;

public class ReportDefinition {

  public ReportDefinition(JasperReport report, JasperReportsContext jasperReportsContext) {
    this.report = report;
    this.jasperReportsContext = jasperReportsContext;
  }

  public JasperReport report;

  /**
   * Context used to resolve resources referenced by the report at fill time (e.g. .jrtx style
   * templates), rooted at the same location the report was loaded from.
   */
  public JasperReportsContext jasperReportsContext;
}
