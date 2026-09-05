package de.pfadfinden.report_engine.preprocessor.Tasks;

import de.pfadfinden.report_engine.preprocessor.AbstractFollowUpTaskCommand;
import de.pfadfinden.report_engine.preprocessor.Metadata.ParameterMetadata;
import de.pfadfinden.report_engine.preprocessor.Metadata.ReportMetadata;
import de.pfadfinden.report_engine.preprocessor.Metadata.VersionMetadata;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.stream.Collectors;
import net.steppschuh.markdowngenerator.table.Table;
import net.steppschuh.markdowngenerator.text.heading.Heading;
import picocli.CommandLine.Command;

@Command(name = "generate-md-details-public")
public class GenerateMarkdownDetailsPublicTask extends AbstractFollowUpTaskCommand {
  protected static final String MAKRDOWN_FILE_SUFFIX = ".md";

  private static String toTableCell(String value) {
    return value == null ? null : value.replace("\r\n", "\n").replace("\n", "<br>");
  }

  protected static final String GROUP_TYPE_PREFIX = "Group::";

  private static String stripGroupTypePrefix(String groupType) {
    return groupType.startsWith(GROUP_TYPE_PREFIX)
        ? groupType.substring(GROUP_TYPE_PREFIX.length())
        : groupType;
  }

  public void export(ReportMetadata report) {
    // A per-report failure here (a metadata.yaml that's valid but sparse - see
    // YamlMetadataReaderTest's minimal-fields case, which leaves outputFormats/parameter/
    // versionHistory null rather than empty - or an unwritable file) must not abort the whole
    // batch: one broken details page shouldn't take every other report's documentation down
    // with it (see CompileJasperReportsTask for the same principle applied to compile failures).
    try {
      List<String> outputFormats = report.outputFormats != null ? report.outputFormats : List.of();
      List<ParameterMetadata> parameters = report.parameter != null ? report.parameter : List.of();
      List<VersionMetadata> versionHistory =
          report.versionHistory != null ? report.versionHistory : List.of();

      File outputDir = new File(this.options.outputDir() + "/details-public/");
      // create folder, if not exists:
      outputDir.mkdirs();

      // build tables contained on detail page:
      Table overviewTable =
          new Table.Builder()
              .addRow("", "")
              .addRow("Beschreibung", report.description)
              .addRow("Ausgabeformate", String.join(", ", outputFormats))
              .addRow(
                  "Gruppentypen",
                  report.onlyForType != null
                      ? report.onlyForType.stream()
                          .map(GenerateMarkdownDetailsPublicTask::stripGroupTypePrefix)
                          .collect(Collectors.joining(", "))
                      : "-")
              .build();

      Table.Builder parameterTable =
          new Table.Builder().addRow("Name", "Beschreibung", "Bemerkung");

      parameters.forEach(
          param ->
              parameterTable.addRow(
                  param.label, toTableCell(param.description), toTableCell(param.comment)));

      Table.Builder versionTable = new Table.Builder().addRow("Version", "Änderung", "Datum");
      versionHistory.forEach(
          version ->
              versionTable.addRow(
                  version.version(), toTableCell(version.description), version.createdOn));

      // write Markdown file:
      try (Writer writer =
          new BufferedWriter(
              new FileWriter(outputDir.getPath() + File.separator + report.id + ".md"))) {

        writer
            .append(new Heading(report.title, 1).toString())
            .append("\n")
            .append(new Heading("Overview", 3).toString())
            .append("\n")
            .append(overviewTable.toString())
            .append("\n")
            .append(new Heading("Parameter", 3).toString())
            .append("\n")
            .append(parameterTable.build().toString())
            .append("\n")
            .append(new Heading("Versionsverlauf", 3).toString())
            .append("\n")
            .append(versionTable.build().toString())
            .append("\n");
      }
    } catch (IOException | RuntimeException e) {
      e.printStackTrace();
      System.out.println("Failed to generate details page for report " + report.id);
    }
  }
}
