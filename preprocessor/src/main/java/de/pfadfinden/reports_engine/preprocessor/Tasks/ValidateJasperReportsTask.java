package de.pfadfinden.reports_engine.preprocessor.Tasks;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import de.pfadfinden.reports_engine.preprocessor.AbstractFollowUpTaskCommand;
import de.pfadfinden.reports_engine.preprocessor.Metadata.ReportMetadata;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "validate-jasper-reports")
public class ValidateJasperReportsTask extends AbstractFollowUpTaskCommand {

    @Option(names = { "--processedReportsDir" }, defaultValue = "processed-reports")
    private String processedReportsDir;

    public void export(ReportMetadata report) {
        File jrxmlFile = getReportSourceFile(report, "report.jrxml");
        if (!(jrxmlFile instanceof File)) {
            return;
        }

        // parameters are listed in metadata
        // parameters are used in report
        // subreports present
        // title matches

        // TODO process jrxml
        /*
         * try {
         * // compile the report
         * JasperCompileManager.compileReportToFile(jrxmlFile.getAbsolutePath(), new
         * File(outputReportDir.toPath() + File.separator +
         * "report.jasper").getAbsolutePath());
         * } catch (JRException e) {
         * e.printStackTrace();
         * System.out.println("Failed to compile report");
         * }
         * 
         * 
         * System.out.println(report.getTitle());
         * 
         * for (JRParameter field : report.getParameters()) {
         * if (field.isSystemDefined()) {
         * continue;
         * }
         * System.out.println(field.getName());
         * System.out.println(field.getDescription());
         * }
         */
    }

}
