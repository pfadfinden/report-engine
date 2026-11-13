package de.pfadfinden.report_engine.preprocessor.Tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.pfadfinden.report_engine.preprocessor.Metadata.ReportMetadata;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenerateMarkdownOverviewTaskTest {

  private static ReportMetadata report(
      String id, String title, String category, String... formats) {
    ReportMetadata r = new ReportMetadata();
    r.id = id;
    r.title = title;
    r.category = category;
    r.description = "Beschreibung " + id;
    r.outputFormats = List.of(formats);
    return r;
  }

  @Test
  void groupsReportsByCategoryAndLinksToDetailPages() {
    String md =
        new GenerateMarkdownOverviewTask()
            .render(
                List.of(
                    report("b_pdf", "Beta", "Statistik", "pdf"),
                    report("a_xls", "Alpha", "Mitgliederlisten", "xlsx"),
                    report("c_pdf", "Ohne Kategorie", "", "pdf", "xlsx")));

    assertTrue(md.startsWith("# Verfügbare Berichte\n"));
    assertTrue(md.contains("| [Alpha](./a_xls.md) | Beschreibung a_xls | XLSX |"));
    assertTrue(md.contains("| [Ohne Kategorie](./c_pdf.md) | Beschreibung c_pdf | PDF, XLSX |"));
    // categories alphabetically, uncategorized last
    assertEquals(
        List.of("## Mitgliederlisten", "## Statistik", "## Weitere Reports"),
        md.lines().filter(l -> l.startsWith("## ")).toList());
  }
}
