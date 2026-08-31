package de.pfadfinden.reports_engine.executor;

import de.pfadfinden.reports_engine.executor.Exceptions.FailedToExportReport;
import de.pfadfinden.reports_engine.executor.Exceptions.FailedToFillReport;
import de.pfadfinden.reports_engine.executor.Exceptions.FailedToLoadReport;
import de.pfadfinden.reports_engine.executor.Exceptions.FailedToStoreReport;
import de.pfadfinden.reports_engine.executor.Port.ReportDefinition;
import de.pfadfinden.reports_engine.executor.Port.ReportLoader;
import de.pfadfinden.reports_engine.executor.Port.ReportOutputStore;
import de.pfadfinden.reports_engine.executor.Port.ReportTaskConfiguration;
import java.io.OutputStream;
import java.sql.Connection;

public class FillReportTaskHandler {

  private ReportLoader reportLoader;
  private ReportOutputStore reportOutputStore;
  private Connection conn;
  private FillReportService fillReportService;

  public FillReportTaskHandler(
      Connection conn, ReportLoader reportLoader, ReportOutputStore reportOutputStore) {
    this.reportLoader = reportLoader;
    this.reportOutputStore = reportOutputStore;
    this.conn = conn;
    this.fillReportService = new FillReportService();
  }

  public void execute(ReportTaskConfiguration task)
      throws FailedToLoadReport, FailedToFillReport, FailedToExportReport, FailedToStoreReport {
    ReportDefinition reportDefinition = this.reportLoader.load(task.reportId);

    OutputStream output = this.reportOutputStore.open(task.outputName);

    this.fillReportService.fill(
        reportDefinition, task.parameters, this.conn, output, task.outputFormat);
  }
}
