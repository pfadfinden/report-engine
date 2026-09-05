package de.pfadfinden.report_engine.preprocessor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.pfadfinden.report_engine.preprocessor.Adapter.SqliteMetadataRepository;
import de.pfadfinden.report_engine.preprocessor.Metadata.ReportMetadata;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.ServiceLoader;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.util.JRLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Integration test: this module (the preprocessor CLI) against its real data sources and
 * destinations - the filesystem (report definitions in, compiled/copied/rendered artifacts out) and
 * the SQLite metadata database it produces. No other module is involved, so this isn't an e2e test
 * (see local-report-executor's ReportExecutionIntegrationTest for the equivalent on the executor
 * side; a true e2e test would additionally involve the frontend talking to the executor).
 *
 * <p>Runs the same task pipeline the mv_reports CI workflow runs against a real reports repository
 * (see mv_reports' .github/workflows/ci.yaml: {@code clean read-metadata copy-source
 * --matchingGlob=* compile-jasper-reports generate-md-details-public generate-md-overview}),
 * against the small report bundle under ../test-fixtures/reports, then checks the produced output
 * against ../test-fixtures/reference-output.
 *
 * <p>The fixture directory is shared with other modules' tests (see ../test-fixtures) rather than
 * living under this module's test resources, since both the preprocessor (which turns report
 * *sources* into deployable output) and the executor (which loads and fills that output) need the
 * same underlying report bundle.
 */
class PipelineIntegrationTest {

  @Test
  void pipelineProducesExpectedOutput(@TempDir File outputDir) throws Exception {
    File fixturesDir = new File("../test-fixtures").getAbsoluteFile();
    File sourceDir = new File(fixturesDir, "reports");
    File referenceDir = new File(fixturesDir, "reference-output");

    int exitCode = runPipeline(sourceDir, outputDir);
    assertEquals(0, exitCode, "preprocessor pipeline should exit successfully");

    // copy-source --matchingGlob=* copies every source file as-is, so the target for these is
    // simply the source fixture itself.
    assertFilesEqual(
        new File(sourceDir, "members/metadata.yaml"), new File(outputDir, "members/metadata.yaml"));
    assertFilesEqual(
        new File(sourceDir, "members/report.jrxml"), new File(outputDir, "members/report.jrxml"));
    assertFilesEqual(
        new File(sourceDir, "_shared/ReportStyles.jrtx"),
        new File(outputDir, "_shared/ReportStyles.jrtx"));
    assertFilesEqual(
        new File(sourceDir, "_shared/logo.png"), new File(outputDir, "_shared/logo.png"));

    // generate-md-details-public / generate-md-overview render new content, so their target is a
    // golden copy captured from a real pipeline run (see ../test-fixtures/reference-output).
    assertFilesEqual(
        new File(referenceDir, "details-public/members.md"),
        new File(outputDir, "details-public/members.md"));
    assertFilesEqual(
        new File(referenceDir, "details-public/index.md"),
        new File(outputDir, "details-public/index.md"));

    assertCompiledReportIsValid(new File(outputDir, "reports/members/report.jasper"));
    assertDatabaseHasExpectedContent(outputDir);
  }

  private int runPipeline(File sourceDir, File outputDir) {
    String[] args = {
      "--source-dir=" + sourceDir,
      "--output-base-dir=" + outputDir,
      "clean",
      "read-metadata",
      "copy-source",
      "--matchingGlob=*",
      "--output-base-dir=" + outputDir,
      "compile-jasper-reports",
      "--output-base-dir=" + outputDir,
      "generate-md-details-public",
      "--output-base-dir=" + outputDir,
      "generate-md-overview",
      "--output-base-dir=" + outputDir,
      "--fileName=./details-public/index.md"
    };

    CommandLine commandLine = new CommandLine(new DefaultCommand());
    ServiceLoader<FollowUpTask> subcommandsLoader = ServiceLoader.load(FollowUpTask.class);
    subcommandsLoader.forEach(sub -> commandLine.addSubcommand(sub));
    return commandLine.execute(args);
  }

  private void assertFilesEqual(File expected, File actual) throws IOException {
    assertTrue(actual.isFile(), "expected output file to exist: " + actual);
    assertArrayEquals(
        Files.readAllBytes(expected.toPath()),
        Files.readAllBytes(actual.toPath()),
        actual + " should match " + expected);
  }

  // report.jasper is JasperReports' own serialized report format: not guaranteed byte-stable
  // across JasperReports patch versions or JVMs, so it's checked structurally instead of
  // byte-diffed against a golden copy.
  private void assertCompiledReportIsValid(File jasperFile) throws Exception {
    assertTrue(jasperFile.isFile(), "compiled report should exist: " + jasperFile);
    JasperReport report = (JasperReport) JRLoader.loadObject(jasperFile);
    assertEquals("members", report.getName());
    List<String> fieldNames =
        List.of(report.getFields()).stream().map(field -> field.getName()).toList();
    assertEquals(List.of("id", "first_name", "last_name", "email", "joined_on"), fieldNames);
  }

  // reports.db is a SQLite file: its page layout isn't guaranteed byte-stable for logically
  // identical data, so its content is checked via a real query instead of a byte-diff against a
  // golden copy.
  private void assertDatabaseHasExpectedContent(File outputDir) {
    SqliteMetadataRepository repository = new SqliteMetadataRepository(outputDir.getPath());
    List<ReportMetadata> reports = repository.all().toList();

    assertEquals(1, reports.size());
    ReportMetadata report = reports.get(0);
    assertEquals("members", report.id);
    assertEquals("Members (Sample)", report.title);
    assertEquals(List.of("pdf", "xlsx"), report.outputFormats);
    assertEquals(1, report.parameter.size());
    assertEquals("p_joined_after", report.parameter.get(0).name());
    assertEquals(1, report.versionHistory.size());
    assertEquals("1.0", report.versionHistory.get(0).version());
  }
}
