package de.pfadfinden.report_engine.preprocessor.Tasks;

import de.pfadfinden.report_engine.preprocessor.AbstractFollowUpTaskCommand;
import de.pfadfinden.report_engine.preprocessor.Metadata.ParameterMetadata;
import de.pfadfinden.report_engine.preprocessor.Metadata.ReportMetadata;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRExpression;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JRSubreport;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.util.JRElementsVisitor;
import net.sf.jasperreports.engine.util.JRVisitorSupport;
import picocli.CommandLine.Command;

/**
 * Cross-checks each report's report.jrxml against its metadata.yaml, catching mistakes that
 * compilation alone wouldn't (a jrxml is happily compilable even if it documents/uses parameters
 * inconsistently, or references a subreport file that was never added to the bundle) before
 * compile-jasper-reports spends time on it.
 */
@Command(
    name = "validate-jasper-reports",
    description =
        "validates that report.jrxml files are consistent with their metadata.yaml before they are compiled")
public class ValidateJasperReportsTask extends AbstractFollowUpTaskCommand {

  // A JRXML expression that is a single static string literal (e.g. "sub.jasper"), as opposed to
  // one built from parameters/fields/concatenation (e.g. $P{SUBREPORT_DIR} + "sub.jasper"), which
  // can only be resolved at fill time and so can't be checked here.
  private static final Pattern STRING_LITERAL_EXPRESSION =
      Pattern.compile("^\"((?:[^\"\\\\]|\\\\.)*)\"$");

  // Mirrors LocalFilesystemReportLoader/RemoteZipReportLoader's fill-time resolution: a resource
  // referenced from a report is looked up first in the report's own directory, then - if not
  // found there - in this directory, common to every report (see MetadataIngestService's
  // reference to the same convention).
  private static final String SHARED_ASSETS_DIR_NAME = "_shared";

  private final List<String> violations = new ArrayList<>();

  @Override
  public void run() {
    super.run();
    if (!violations.isEmpty()) {
      throw new RuntimeException(
          "Found "
              + violations.size()
              + " report validation issue(s):\n"
              + String.join("\n", violations));
    }
  }

  public void export(ReportMetadata report) {
    File jrxmlFile = getReportSourceFile(report, "report.jrxml");
    if (jrxmlFile == null) {
      return;
    }

    JasperReport compiledReport;
    try {
      compiledReport = JasperCompileManager.compileReport(jrxmlFile.getAbsolutePath());
    } catch (JRException e) {
      violations.add(report.id + ": report.jrxml failed to compile: " + e.getMessage());
      return;
    }

    validateParameters(report, compiledReport);
    validateSubreports(report, compiledReport, jrxmlFile.getParentFile());
  }

  private void validateParameters(ReportMetadata report, JasperReport compiledReport) {
    Set<String> reportParameters = new LinkedHashSet<>();
    for (JRParameter parameter : compiledReport.getParameters()) {
      if (!parameter.isSystemDefined()) {
        reportParameters.add(parameter.getName());
      }
    }

    Set<String> documentedParameters = new LinkedHashSet<>();
    List<ParameterMetadata> parameterMetadata =
        report.parameter != null ? report.parameter : List.of();
    for (ParameterMetadata parameter : parameterMetadata) {
      documentedParameters.add(parameter.name());
    }

    for (String name : reportParameters) {
      if (!documentedParameters.contains(name)) {
        violations.add(
            report.id
                + ": parameter '"
                + name
                + "' is used in report.jrxml but not documented in metadata.yaml");
      }
    }

    for (String name : documentedParameters) {
      if (!reportParameters.contains(name)) {
        violations.add(
            report.id
                + ": parameter '"
                + name
                + "' is documented in metadata.yaml but not used in report.jrxml");
      }
    }
  }

  private void validateSubreports(
      ReportMetadata report, JasperReport compiledReport, File reportDir) {
    List<JRSubreport> subreports = new ArrayList<>();
    JRElementsVisitor.visitReport(
        compiledReport,
        new JRVisitorSupport() {
          @Override
          public void visitSubreport(JRSubreport subreport) {
            subreports.add(subreport);
          }
        });

    File sourceDir = reportDir.getParentFile();
    File sharedAssetsDir = sourceDir != null ? new File(sourceDir, SHARED_ASSETS_DIR_NAME) : null;

    for (JRSubreport subreport : subreports) {
      JRExpression expression = subreport.getExpression();
      String expressionText = expression != null ? expression.getText() : null;
      if (expressionText == null) {
        continue;
      }

      Matcher matcher = STRING_LITERAL_EXPRESSION.matcher(expressionText.trim());
      if (!matcher.matches()) {
        // built dynamically (parameters, concatenation, ...) - only resolvable at fill time.
        continue;
      }

      String subreportPath = matcher.group(1);
      if (!existsUnder(reportDir, subreportPath) && !existsUnder(sharedAssetsDir, subreportPath)) {
        violations.add(
            report.id
                + ": subreport '"
                + subreportPath
                + "' referenced in report.jrxml was not found in the report's own directory or in "
                + SHARED_ASSETS_DIR_NAME);
      }
    }
  }

  // Canonicalizes before checking existence, so a report.jrxml referencing something outside
  // baseDir (e.g. via "..") is treated as unresolvable rather than followed.
  private boolean existsUnder(File baseDir, String relativePath) {
    if (baseDir == null) {
      return false;
    }
    try {
      File canonicalBaseDir = baseDir.getCanonicalFile();
      File canonicalFile = new File(canonicalBaseDir, relativePath).getCanonicalFile();
      return canonicalFile.getPath().startsWith(canonicalBaseDir.getPath() + File.separator)
          && canonicalFile.isFile();
    } catch (IOException e) {
      return true; // can't resolve either path - don't report a false violation
    }
  }
}
