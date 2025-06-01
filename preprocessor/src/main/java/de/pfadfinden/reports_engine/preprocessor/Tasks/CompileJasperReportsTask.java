package de.pfadfinden.reports_engine.preprocessor.Tasks;

import java.io.File;
import de.pfadfinden.reports_engine.preprocessor.AbstractFollowUpTaskCommand;
import de.pfadfinden.reports_engine.preprocessor.Metadata.ReportMetadata;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "compile-jasper-reports", description = "compiles report.jrxml files to report.jasper files")
public class CompileJasperReportsTask extends AbstractFollowUpTaskCommand {

    @Option(names = { "--processedReportsDir" }, defaultValue = "reports")
    private String processedReportsDir;

    public void export(ReportMetadata report) {
        System.out.println(this.options.outputDir().toPath());
        System.out.println(this.parent.inputDir().toPath());

        File jrxmlFile = getReportSourceFile(report, "report.jrxml");
        if (!(jrxmlFile instanceof File)) {
            return;
        }

        File outputReportDir = new File(this.options.outputDir().toPath() + File.separator + this.processedReportsDir
                + File.separator + report.id + File.separator);
        outputReportDir.mkdirs(); // create report folder if it does not exist

        try {
            // compile the report
            JasperCompileManager.compileReportToFile(jrxmlFile.getAbsolutePath(),
                    new File(outputReportDir.toPath() + File.separator + "report.jasper").getAbsolutePath());
        } catch (JRException e) {
            e.printStackTrace();
            System.out.println("Failed to compile report");
        }
    }

}
