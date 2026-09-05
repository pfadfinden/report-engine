package de.pfadfinden.report_engine.local_report_executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.pfadfinden.report_engine.executor.Adapter.Storage.Filesystem.LocalFilesystemReportLoader;
import de.pfadfinden.report_engine.executor.FillReportService;
import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import de.pfadfinden.report_engine.executor.Port.ReportDefinition;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.sf.jasperreports.engine.JRPrintText;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.util.JRSaver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test: this module's report-execution machinery ({@link LocalFilesystemReportLoader} +
 * {@link FillReportService}, the exact wiring {@link ReportExecutionRunner} uses in production)
 * against its real data source - a Postgres database - using the report bundle shared across
 * modules (see ../../test-fixtures). No other module (in particular, no frontend) is involved, so
 * this isn't an e2e test under the frontend-executor definition; it's a module-to-datasource
 * integration test (see preprocessor's PipelineIntegrationTest for the equivalent on the
 * preprocessor side).
 *
 * <p>Needs a real Postgres reachable at {@code REPORT_SOURCEDATA_DATABASE_URL} - the same
 * environment variable {@link Config#fromEnv()} reads in production (see docker-compose.yml) - and
 * is skipped otherwise, e.g. when run locally without one. In CI this should point at a throwaway
 * Postgres service container; nothing here needs Testcontainers or any other new dependency, since
 * the Postgres driver is already a dependency of this module.
 *
 * <p>The seed data is loaded and the whole fill runs inside one transaction that is always rolled
 * back afterwards, so the test never leaves data behind and can be re-run against the same database
 * indefinitely (seed.sql uses explicit ids for the same reason - Postgres sequences aren't
 * transactional, so relying on SERIAL defaults would drift ids on every re-run).
 */
class ReportExecutionIntegrationTest {

  @Test
  void fillsRealReportAgainstRealDatabaseAndMatchesReference(@TempDir File tempReportsDir)
      throws Exception {
    String jdbcUrl = System.getenv("REPORT_SOURCEDATA_DATABASE_URL");
    assumeTrue(
        jdbcUrl != null && !jdbcUrl.isBlank(),
        "REPORT_SOURCEDATA_DATABASE_URL not set - skipping integration test that needs a real"
            + " Postgres");

    File fixturesDir = new File("../test-fixtures").getAbsoluteFile();
    File sourceReportDir = new File(fixturesDir, "reports/members");
    File sharedAssetsDir = new File(fixturesDir, "reports/_shared");

    compileReport(sourceReportDir, tempReportsDir);

    try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
      conn.setAutoCommit(false);
      try {
        seedDatabase(conn, new File(fixturesDir, "seed.sql"));

        LocalFilesystemReportLoader loader =
            new LocalFilesystemReportLoader(tempReportsDir, sharedAssetsDir);
        ReportDefinition reportDefinition = loader.load("members");

        // Exercises the exact fill+export path ReportExecutionRunner uses in production.
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        new FillReportService().fill(reportDefinition, parameters(), conn, pdf, OutputFormat.PDF);
        assertTrue(pdf.size() > 0, "expected a non-empty exported PDF");

        // Re-fills (same connection/transaction, so it sees the same seeded rows) to inspect what
        // was actually rendered - PDF bytes aren't something this module can safely parse back.
        // Uses its own fresh parameters map: JasperFillManager.fill writes its own
        // REPORT_CONNECTION entry into whatever map it's given.
        JasperPrint printed =
            JasperFillManager.getInstance(reportDefinition.jasperReportsContext)
                .fill(reportDefinition.report, parameters(), conn);

        List<List<String>> renderedRows = extractDetailRows(printed);
        List<List<String>> referenceRows =
            readReferenceCsv(new File(fixturesDir, "reference-output/members-filtered.csv"));

        assertEquals(referenceRows, renderedRows);
      } finally {
        conn.rollback();
      }
    }
  }

  private Map<String, Object> parameters() {
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("p_joined_after", Date.valueOf(LocalDate.of(2021, 1, 1)));
    return parameters;
  }

  private void compileReport(File sourceReportDir, File tempReportsDir) throws Exception {
    File reportOutDir = new File(tempReportsDir, "members");
    reportOutDir.mkdirs();
    JasperReport report =
        JasperCompileManager.compileReport(new File(sourceReportDir, "report.jrxml").getPath());
    JRSaver.saveObject(report, new File(reportOutDir, "report.jasper"));
  }

  private void seedDatabase(Connection conn, File seedSqlFile) throws IOException, SQLException {
    String sql = Files.readString(seedSqlFile.toPath());
    try (Statement statement = conn.createStatement()) {
      for (String single : sql.split(";")) {
        String trimmed = single.strip();
        if (!trimmed.isEmpty()) {
          statement.execute(trimmed);
        }
      }
    }
  }

  // Rows are rendered by the "DetailText"-styled text fields laid out left-to-right in the detail
  // band (see report.jrxml): grouping the printed elements by Y (one group per rendered row) and
  // sorting each group by X reconstructs each row in field order (id, first_name, last_name,
  // email, joined_on), regardless of absolute page coordinates.
  private List<List<String>> extractDetailRows(JasperPrint printed) {
    Map<Integer, List<JRPrintText>> rowsByY = new TreeMap<>();
    printed
        .getPages()
        .forEach(
            page ->
                page.getElements().stream()
                    .filter(element -> element instanceof JRPrintText)
                    .map(element -> (JRPrintText) element)
                    .filter(
                        text ->
                            text.getStyle() != null
                                && "DetailText".equals(text.getStyle().getName()))
                    .forEach(
                        text ->
                            rowsByY
                                .computeIfAbsent(text.getY(), y -> new ArrayList<>())
                                .add(text)));

    List<List<String>> rows = new ArrayList<>();
    for (List<JRPrintText> rowElements : rowsByY.values()) {
      rowElements.sort((a, b) -> Integer.compare(a.getX(), b.getX()));
      rows.add(rowElements.stream().map(JRPrintText::getFullText).toList());
    }
    return rows;
  }

  private List<List<String>> readReferenceCsv(File csvFile) throws IOException {
    return Files.readAllLines(csvFile.toPath()).stream()
        .skip(1) // header row
        .filter(line -> !line.isBlank())
        .map(line -> List.of(line.split(",", -1)))
        .toList();
  }
}
