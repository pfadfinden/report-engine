package de.pfadfinden.reports_engine.executor;

import de.pfadfinden.reports_engine.executor.Exceptions.FailedToExportReport;
import de.pfadfinden.reports_engine.executor.Exceptions.FailedToFillReport;
import de.pfadfinden.reports_engine.executor.Port.OutputFormat;
import de.pfadfinden.reports_engine.executor.Port.ReportDefinition;
import java.io.OutputStream;
import java.sql.Connection;
import java.util.Map;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.pdf.JRPdfExporter;

public class FillReportService {
  public void fill(
      ReportDefinition reportDefinition,
      Map<String, Object> parameter,
      Connection conn,
      OutputStream output,
      OutputFormat format)
      throws FailedToFillReport, FailedToExportReport {
    JasperPrint filledReport;
    try {
      filledReport =
          JasperFillManager.getInstance(reportDefinition.jasperReportsContext)
              .fill(reportDefinition.report, parameter, conn);
    } catch (JRException e) {
      throw new FailedToFillReport(e);
    }

    try {
      // Exporters must be given the report's own JasperReportsContext, not the
      // parameterless constructor's global default one - that's what carries the
      // font family extensions LocalFilesystemReportLoader registered from the
      // report's fonts.xml. Without it, a custom embedded font (e.g. one declared
      // with pdfEncoding="Identity-H") resolves to nothing and PDF export fails
      // with "Could not load the following font: pdfFontName: Helvetica".
      switch (format) {
        case PDF -> {
          JRPdfExporter exporter = new JRPdfExporter(reportDefinition.jasperReportsContext);
          exporter.setExporterInput(new SimpleExporterInput(filledReport));
          exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(output));
          exporter.exportReport();
        }
        case XLSX -> {
          JRXlsxExporter exporter = new JRXlsxExporter(reportDefinition.jasperReportsContext);
          exporter.setExporterInput(new SimpleExporterInput(filledReport));
          exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(output));
          exporter.exportReport();
        }
      }
    } catch (JRException e) {
      throw new FailedToExportReport(e);
    }
  }
}
