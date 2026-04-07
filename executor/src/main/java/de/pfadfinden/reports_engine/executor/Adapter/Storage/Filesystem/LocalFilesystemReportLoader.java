package de.pfadfinden.reports_engine.executor.Adapter.Storage.Filesystem;

import java.io.File;

import de.pfadfinden.reports_engine.executor.Exceptions.FailedToLoadReport;
import de.pfadfinden.reports_engine.executor.Port.ReportDefinition;
import de.pfadfinden.reports_engine.executor.Port.ReportLoader;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;

public class LocalFilesystemReportLoader implements ReportLoader {

    @Override
    public ReportDefinition load(String reportName) throws FailedToLoadReport {
            File reportJRXml = new File("./" + reportName);
            System.out.println(reportJRXml.canRead());
            //   JasperDesign report = JRXmlLoader.load(reportJRXml);
            // TODO JRTX when present?
            JasperReport report;
            try {
                report = JasperCompileManager.compileReport("./bdp_mitglieder_xls_v1.jrxml");
            } catch (JRException e) {
                throw new FailedToLoadReport(e);
            }
            return new ReportDefinition(report);
    }

}
