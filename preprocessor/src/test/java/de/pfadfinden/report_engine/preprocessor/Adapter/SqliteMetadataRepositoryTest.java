package de.pfadfinden.report_engine.preprocessor.Adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.pfadfinden.report_engine.preprocessor.Metadata.ParameterMetadata;
import de.pfadfinden.report_engine.preprocessor.Metadata.ParameterOption;
import de.pfadfinden.report_engine.preprocessor.Metadata.ReportMetadata;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one test in this module that goes through a real SQLite database rather than mocking
 * persistence - SQLite is file-backed and embedded, so a temp directory is enough; no
 * Testcontainers/external service needed.
 */
class SqliteMetadataRepositoryTest {

  private ReportMetadata report(String id, String title, List<String> outputFormats) {
    ReportMetadata report = new ReportMetadata();
    report.id = id;
    report.title = title;
    report.outputFormats = outputFormats;
    return report;
  }

  @Test
  void addedReportsCanBeReadBackViaAll(@TempDir File dir) {
    SqliteMetadataRepository repository = SqliteMetadataRepository.initNew(dir.getAbsolutePath());

    repository.add(report("list_of_members", "List of Members", List.of("pdf", "xlsx")));

    List<ReportMetadata> all = repository.all().toList();
    assertEquals(1, all.size());
    assertEquals("list_of_members", all.get(0).id);
    assertEquals("List of Members", all.get(0).title);
    assertEquals(List.of("pdf", "xlsx"), all.get(0).outputFormats);
  }

  @Test
  void allReturnsEveryAddedReport(@TempDir File dir) {
    SqliteMetadataRepository repository = SqliteMetadataRepository.initNew(dir.getAbsolutePath());

    repository.add(report("report-a", "Report A", List.of("pdf")));
    repository.add(report("report-b", "Report B", List.of("csv")));

    List<String> ids = repository.all().map(r -> r.id).sorted().toList();
    assertEquals(List.of("report-a", "report-b"), ids);
  }

  @Test
  void selectParameterOptionsAreStoredInOrder(@TempDir File dir) {
    ReportMetadata report = report("list_of_members", "List of Members", List.of("pdf"));
    ParameterMetadata parameter = new ParameterMetadata();
    parameter.setName("A_Taetigkeit1");
    parameter.setReport(report);
    parameter.type = "select";
    parameter.options.add(new ParameterOption("176", "AK Leiter*in"));
    parameter.options.add(new ParameterOption("138", "Landesbeauftragte*r"));
    report.parameter = new ArrayList<>(List.of(parameter));

    SqliteMetadataRepository.initNew(dir.getAbsolutePath()).add(report);
    // Read back through a fresh connection, so this checks what actually landed in the database
    // rather than the entity instances still cached in the writer's EntityManager.
    SqliteMetadataRepository reader = new SqliteMetadataRepository(dir.getAbsolutePath());

    ParameterMetadata stored = reader.all().findFirst().orElseThrow().parameter.get(0);
    assertEquals("A_Taetigkeit1", stored.name());
    assertEquals(
        List.of("176", "138"), stored.options.stream().map(option -> option.value).toList());
    assertEquals(
        List.of("AK Leiter*in", "Landesbeauftragte*r"),
        stored.options.stream().map(option -> option.label).toList());
  }

  @Test
  void reopeningAnExistingDatabaseSeesPreviouslyAddedReports(@TempDir File dir) {
    SqliteMetadataRepository writer = SqliteMetadataRepository.initNew(dir.getAbsolutePath());
    writer.add(report("list_of_members", "List of Members", List.of("pdf")));

    SqliteMetadataRepository reader = new SqliteMetadataRepository(dir.getAbsolutePath());

    Optional<ReportMetadata> found =
        reader.all().filter(r -> r.id.equals("list_of_members")).findFirst();
    assertTrue(found.isPresent());
  }
}
