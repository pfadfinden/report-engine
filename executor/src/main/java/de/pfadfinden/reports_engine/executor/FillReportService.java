package de.pfadfinden.reports_engine.executor;

import java.io.OutputStream;
import java.sql.Connection;
import java.util.Map;

import de.pfadfinden.reports_engine.executor.Exceptions.FailedToExportReport;
import de.pfadfinden.reports_engine.executor.Exceptions.FailedToFillReport;
import de.pfadfinden.reports_engine.executor.Port.ReportDefinition;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;

public class FillReportService {
    public void fill(ReportDefinition reportDefinition, Map<String, Object> parameter, Connection conn, OutputStream output) throws FailedToFillReport, FailedToExportReport {
        JasperPrint filledReport;
        try {
            filledReport = JasperFillManager.fillReport(reportDefinition.report, parameter, conn);
        } catch (JRException e) {
            throw new FailedToFillReport(e);
        }

        try {
            // TODO select exporter

            JRXlsxExporter exporter = new JRXlsxExporter();
            exporter.setExporterInput(new SimpleExporterInput(filledReport));
            exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(output));
           
            exporter.exportReport();
            
        } catch (JRException e) {
            throw new FailedToExportReport(e);
        }

    }
}
