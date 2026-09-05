package de.pfadfinden.report_engine.preprocessor.Tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.pfadfinden.report_engine.preprocessor.DefaultCommand;
import de.pfadfinden.report_engine.preprocessor.FollowUpTask;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Covers the individual checks ValidateJasperReportsTask runs. PipelineIntegrationTest's fixture is
 * entirely consistent (no undocumented/unused parameters, no subreports), so it only proves the
 * happy path stays quiet - the failure paths need their own, deliberately inconsistent report
 * bundles.
 *
 * <p>Goes through the real "read-metadata" + "validate-jasper-reports" CLI pipeline rather than
 * calling export(ReportMetadata) directly, since AbstractFollowUpTaskCommand's parent/options
 * fields are populated by picocli (@ParentCommand/@Mixin) and aren't otherwise settable from a
 * test.
 */
class ValidateJasperReportsTaskTest {

  private record Result(int exitCode, Throwable executionException) {}

  private void writeReport(File sourceDir, String id, String metadataYaml, String reportJrxml)
      throws IOException {
    File reportDir = new File(sourceDir, id);
    reportDir.mkdirs();
    Files.writeString(
        new File(reportDir, "metadata.yaml").toPath(), metadataYaml, StandardCharsets.UTF_8);
    Files.writeString(
        new File(reportDir, "report.jrxml").toPath(), reportJrxml, StandardCharsets.UTF_8);
  }

  private Result runValidation(File sourceDir, File outputDir) {
    String[] args = {
      "--source-dir=" + sourceDir,
      "--output-base-dir=" + outputDir,
      "read-metadata",
      "validate-jasper-reports",
      "--output-base-dir=" + outputDir
    };

    CommandLine commandLine = new CommandLine(new DefaultCommand());
    ServiceLoader<FollowUpTask> subcommandsLoader = ServiceLoader.load(FollowUpTask.class);
    subcommandsLoader.forEach(sub -> commandLine.addSubcommand(sub));

    AtomicReference<Throwable> capturedException = new AtomicReference<>();
    commandLine.setExecutionExceptionHandler(
        (exception, cl, parseResult) -> {
          capturedException.set(exception);
          return cl.getCommandSpec().exitCodeOnExecutionException();
        });

    int exitCode = commandLine.execute(args);
    return new Result(exitCode, capturedException.get());
  }

  @Test
  void passesWhenParametersAndSubreportsAreConsistent(
      @TempDir File sourceDir, @TempDir File outputDir) throws IOException {
    writeReport(
        sourceDir,
        "consistent",
        """
        id: consistent
        title: Consistent report
        parameter:
            - name: p_used
              label: Used parameter
        """,
        """
        <jasperReport name="consistent" language="java">
          <parameter name="p_used" class="java.lang.String"/>
        </jasperReport>
        """);

    Result result = runValidation(sourceDir, outputDir);

    assertEquals(0, result.exitCode());
  }

  @Test
  void flagsParameterUsedInReportButNotDocumented(@TempDir File sourceDir, @TempDir File outputDir)
      throws IOException {
    writeReport(
        sourceDir,
        "undocumented_param",
        """
        id: undocumented_param
        title: Report with an undocumented parameter
        """,
        """
        <jasperReport name="undocumented_param" language="java">
          <parameter name="p_undocumented" class="java.lang.String"/>
        </jasperReport>
        """);

    String message = executionExceptionMessage(runValidation(sourceDir, outputDir));

    assertTrue(
        message.contains(
            "p_undocumented' is used in report.jrxml but not documented in metadata.yaml"),
        message);
  }

  @Test
  void flagsParameterDocumentedButNotUsedInReport(@TempDir File sourceDir, @TempDir File outputDir)
      throws IOException {
    writeReport(
        sourceDir,
        "unused_param",
        """
        id: unused_param
        title: Report with an unused documented parameter
        parameter:
            - name: p_unused
              label: Unused parameter
        """,
        """
        <jasperReport name="unused_param" language="java">
        </jasperReport>
        """);

    String message = executionExceptionMessage(runValidation(sourceDir, outputDir));

    assertTrue(
        message.contains("p_unused' is documented in metadata.yaml but not used in report.jrxml"),
        message);
  }

  @Test
  void flagsMissingSubreportFile(@TempDir File sourceDir, @TempDir File outputDir)
      throws IOException {
    writeReport(
        sourceDir,
        "missing_subreport",
        """
        id: missing_subreport
        title: Report with a missing subreport
        """,
        """
        <jasperReport name="missing_subreport" language="java">
          <detail>
            <band height="20">
              <element kind="subreport" x="0" y="0" width="200" height="20" uuid="9a1b2c3d-4e5f-4a1b-8c2d-3e4f5a6b7c8d">
                <expression><![CDATA["nested/sub.jasper"]]></expression>
              </element>
            </band>
          </detail>
        </jasperReport>
        """);

    String message = executionExceptionMessage(runValidation(sourceDir, outputDir));

    assertTrue(
        message.contains("subreport 'nested/sub.jasper'")
            && message.contains("was not found in the report's own directory or in _shared"),
        message);
  }

  @Test
  void passesWhenReferencedSubreportFileExists(@TempDir File sourceDir, @TempDir File outputDir)
      throws IOException {
    writeReport(
        sourceDir,
        "present_subreport",
        """
        id: present_subreport
        title: Report with an existing subreport
        """,
        """
        <jasperReport name="present_subreport" language="java">
          <detail>
            <band height="20">
              <element kind="subreport" x="0" y="0" width="200" height="20" uuid="9a1b2c3d-4e5f-4a1b-8c2d-3e4f5a6b7c8d">
                <expression><![CDATA["nested/sub.jasper"]]></expression>
              </element>
            </band>
          </detail>
        </jasperReport>
        """);
    File nestedDir = new File(new File(sourceDir, "present_subreport"), "nested");
    nestedDir.mkdirs();
    Files.writeString(new File(nestedDir, "sub.jasper").toPath(), "not a real compiled report");

    Result result = runValidation(sourceDir, outputDir);

    assertEquals(0, result.exitCode());
  }

  @Test
  void passesWhenReferencedSubreportFileExistsOnlyInSharedDir(
      @TempDir File sourceDir, @TempDir File outputDir) throws IOException {
    writeReport(
        sourceDir,
        "shared_subreport",
        """
        id: shared_subreport
        title: Report referencing a subreport that lives in _shared
        """,
        """
        <jasperReport name="shared_subreport" language="java">
          <detail>
            <band height="20">
              <element kind="subreport" x="0" y="0" width="200" height="20" uuid="9a1b2c3d-4e5f-4a1b-8c2d-3e4f5a6b7c8d">
                <expression><![CDATA["chart.jasper"]]></expression>
              </element>
            </band>
          </detail>
        </jasperReport>
        """);
    // not next to the report itself - only reachable via the shared assets dir, same as
    // mv_reports' real report definitions reference _shared/AA_bdp_chart_bar.jrxml et al.
    File sharedDir = new File(sourceDir, "_shared");
    sharedDir.mkdirs();
    Files.writeString(new File(sharedDir, "chart.jasper").toPath(), "not a real compiled report");

    Result result = runValidation(sourceDir, outputDir);

    assertEquals(0, result.exitCode());
  }

  @Test
  void flagsReportThatFailsToCompile(@TempDir File sourceDir, @TempDir File outputDir)
      throws IOException {
    writeReport(
        sourceDir,
        "broken",
        """
        id: broken
        title: Report that fails to compile
        """,
        """
        <jasperReport name="broken" language="java">
          <parameter name="p1" class="java.lang.String">
            <defaultValueExpression><![CDATA[this is not valid java]]></defaultValueExpression>
          </parameter>
        </jasperReport>
        """);

    String message = executionExceptionMessage(runValidation(sourceDir, outputDir));

    assertTrue(message.contains("report.jrxml failed to compile"), message);
  }

  private String executionExceptionMessage(Result result) {
    assertNotEquals(0, result.exitCode());
    Throwable exception = result.executionException();
    assertNotNull(exception, "expected validation to throw");
    return exception.getMessage();
  }
}
