package de.pfadfinden.report_engine.executor;

import de.pfadfinden.report_engine.executor.Exceptions.FailedToExportReport;
import de.pfadfinden.report_engine.executor.Exceptions.FailedToFillReport;
import de.pfadfinden.report_engine.executor.Exceptions.FailedToLoadReport;
import de.pfadfinden.report_engine.executor.Exceptions.FailedToStoreReport;
import de.pfadfinden.report_engine.executor.Observability.TracingReportFiller;
import de.pfadfinden.report_engine.executor.Port.ReportDefinition;
import de.pfadfinden.report_engine.executor.Port.ReportLoader;
import de.pfadfinden.report_engine.executor.Port.ReportOutputStore;
import de.pfadfinden.report_engine.executor.Port.ReportTaskConfiguration;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;

public class FillReportTaskHandler {

  private ReportLoader reportLoader;
  private ReportOutputStore reportOutputStore;
  private Connection conn;
  private ReportFiller reportFiller;

  public FillReportTaskHandler(
      Connection conn, ReportLoader reportLoader, ReportOutputStore reportOutputStore) {
    this.reportLoader = reportLoader;
    this.reportOutputStore = reportOutputStore;
    this.conn = conn;
    this.reportFiller = new TracingReportFiller(new FillReportService());
  }

  public void execute(ReportTaskConfiguration task)
      throws FailedToLoadReport, FailedToFillReport, FailedToExportReport, FailedToStoreReport {
    ReportDefinition reportDefinition = this.reportLoader.load(task.reportId);

    // try-with-resources rather than a bare open(): FillReportService's exporters close the
    // stream themselves on a successful export, but a JRException (fill or export) or a DB
    // failure thrown before that point previously left the stream (a file/blob handle) open
    // forever - close() on an already-closed stream is a no-op, so this is safe either way.
    try (OutputStream output = this.reportOutputStore.open(task.outputName)) {
      this.reportFiller.fill(
          reportDefinition, task.parameters, this.conn, output, task.outputFormat);
    } catch (IOException e) {
      throw new FailedToStoreReport(e);
    }
  }
}
