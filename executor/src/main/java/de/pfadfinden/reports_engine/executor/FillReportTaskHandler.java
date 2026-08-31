package de.pfadfinden.reports_engine.executor;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.sql.Connection;

import de.pfadfinden.reports_engine.executor.Exceptions.FailedToExportReport;
import de.pfadfinden.reports_engine.executor.Exceptions.FailedToFillReport;
import de.pfadfinden.reports_engine.executor.Exceptions.FailedToLoadReport;
import de.pfadfinden.reports_engine.executor.Exceptions.FailedToStoreReport;
import de.pfadfinden.reports_engine.executor.Port.OutputFormat;
import de.pfadfinden.reports_engine.executor.Port.ReportDefinition;
import de.pfadfinden.reports_engine.executor.Port.ReportLoader;
import de.pfadfinden.reports_engine.executor.Port.ReportTaskConfiguration;

public class FillReportTaskHandler {

    private ReportLoader reportLoader;
    private Connection conn;
    private FillReportService fillReportService;

    public FillReportTaskHandler(Connection conn, ReportLoader reportLoader) {
        this.reportLoader = reportLoader;
        this.conn = conn;
        this.fillReportService = new FillReportService();
    }

    public void execute(ReportTaskConfiguration task) throws FailedToLoadReport, FailedToFillReport, FailedToExportReport, FailedToStoreReport {
            ReportDefinition reportDefinition = this.reportLoader.load(task.reportId);

            // Todo interface output
            OutputStream output;
            try {
                output = new FileOutputStream(task.outputName);
            } catch (FileNotFoundException e) {
                throw new FailedToStoreReport(e);
            }
            
            // Todo interface output format - this handler has no caller yet (see
            // ReportExecutionRunner's doc comment), so there's no request to derive it from.
            this.fillReportService.fill(reportDefinition, task.parameters, this.conn, output, OutputFormat.XLSX);
    }
}
