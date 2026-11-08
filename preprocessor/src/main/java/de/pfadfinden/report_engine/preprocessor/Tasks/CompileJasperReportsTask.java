package de.pfadfinden.report_engine.preprocessor.Tasks;

import de.pfadfinden.report_engine.preprocessor.AbstractFollowUpTaskCommand;
import de.pfadfinden.report_engine.preprocessor.Metadata.ReportMetadata;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "compile-jasper-reports",
    description = "compiles report.jrxml files to report.jasper files")
public class CompileJasperReportsTask extends AbstractFollowUpTaskCommand {

  @Option(
      names = {"--processedReportsDir"},
      defaultValue = "reports")
  private String processedReportsDir;

  private final List<String> failedReportIds = new ArrayList<>();

  @Override
  public void run() {
    super.run();
    if (!failedReportIds.isEmpty()) {
      throw new RuntimeException(
          "Failed to compile "
              + failedReportIds.size()
              + " report(s): "
              + String.join(", ", failedReportIds));
    }
  }

  public void export(ReportMetadata report) {
    System.out.println(this.options.outputDir().toPath());
    System.out.println(this.parent.inputDir().toPath());

    File jrxmlFile = getReportSourceFile(report, "report.jrxml");
    if (jrxmlFile == null) {
      return;
    }

    File outputReportDir =
        new File(
            this.options.outputDir().toPath()
                + File.separator
                + this.processedReportsDir
                + File.separator
                + report.id
                + File.separator);
    outputReportDir.mkdirs(); // create report folder if it does not exist

    try {
      // compile the report
      JasperCompileManager.compileReportToFile(
          jrxmlFile.getAbsolutePath(),
          new File(outputReportDir.toPath() + File.separator + "report.jasper").getAbsolutePath());
      copyRuntimeFiles(jrxmlFile.getParentFile(), outputReportDir);
    } catch (JRException | IOException e) {
      e.printStackTrace();
      System.out.println("Failed to compile report " + report.id);
      failedReportIds.add(report.id);
    }
  }

  // Files the compiled report references from its own directory at fill time (subreports such as
  // "./sub.jasper", images, ...) are resolved next to report.jasper, so they have to be shipped
  // there. Subreports are compiled from their .jrxml so a stale committed .jasper never ships;
  // other sources and metadata are not needed at runtime.
  private void copyRuntimeFiles(File sourceReportDir, File outputReportDir)
      throws IOException, JRException {
    File[] files = sourceReportDir.listFiles(File::isFile);
    if (files == null) {
      return;
    }
    for (File file : files) {
      String name = file.getName();
      if (name.endsWith(".jrxml") || name.equals("metadata.yaml") || name.equals("report.jasper")) {
        continue;
      }
      Files.copy(
          file.toPath(),
          new File(outputReportDir, name).toPath(),
          StandardCopyOption.REPLACE_EXISTING);
    }
    for (File file : files) {
      String name = file.getName();
      if (!name.endsWith(".jrxml") || name.equals("report.jrxml")) {
        continue;
      }
      String compiledName = name.substring(0, name.length() - ".jrxml".length()) + ".jasper";
      JasperCompileManager.compileReportToFile(
          file.getAbsolutePath(), new File(outputReportDir, compiledName).getAbsolutePath());
    }
  }
}
