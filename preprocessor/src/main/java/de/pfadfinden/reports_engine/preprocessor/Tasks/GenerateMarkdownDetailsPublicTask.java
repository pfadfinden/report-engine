package de.pfadfinden.reports_engine.preprocessor.Tasks;

import de.pfadfinden.reports_engine.preprocessor.AbstractFollowUpTaskCommand;
import de.pfadfinden.reports_engine.preprocessor.Metadata.ReportMetadata;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
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
    File outputDir = new File(this.options.outputDir() + "/details-public/");
    // create folder, if not exists:
    outputDir.mkdirs();

    // build tables contained on detail page:
    Table overviewTable =
        new Table.Builder()
            .addRow("", "")
            .addRow("Beschreibung", report.description)
            .addRow("Ausgabeformate", String.join(", ", report.outputFormats))
            .addRow(
                "Gruppentypen",
                report.onlyForType != null
                    ? report.onlyForType.stream()
                        .map(GenerateMarkdownDetailsPublicTask::stripGroupTypePrefix)
                        .collect(Collectors.joining(", "))
                    : "-")
            .build();

    Table.Builder parameterTable = new Table.Builder().addRow("Name", "Beschreibung", "Bemerkung");

    report.parameter.forEach(
        param ->
            parameterTable.addRow(
                param.label, toTableCell(param.description), toTableCell(param.comment)));

    Table.Builder versionTable = new Table.Builder().addRow("Version", "Änderung", "Datum");
    report.versionHistory.forEach(
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

    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
