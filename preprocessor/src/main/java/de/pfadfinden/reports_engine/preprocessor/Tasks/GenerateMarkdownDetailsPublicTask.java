package de.pfadfinden.reports_engine.preprocessor.Tasks;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;

import de.pfadfinden.reports_engine.preprocessor.AbstractFollowUpTaskCommand;
import de.pfadfinden.reports_engine.preprocessor.Metadata.ReportMetadata;
import net.steppschuh.markdowngenerator.table.Table;
import net.steppschuh.markdowngenerator.text.heading.Heading;
import picocli.CommandLine.Command;

@Command(name = "generate-md-details-public")
public class GenerateMarkdownDetailsPublicTask extends AbstractFollowUpTaskCommand {
    protected final static String MAKRDOWN_FILE_SUFFIX = ".md";

    public void export(ReportMetadata report) {
        File outputDir = new File(this.options.outputDir() + "/details-public/");
        // create folder, if not exists:
        outputDir.mkdirs();

        // build tables contained on detail page:
        Table overviewTable = new Table.Builder()
                .addRow("", "")
                .addRow("Beschreibung", report.description)
                .addRow("Ausgabeformate", String.join(", ", report.outputFormats))
                .addRow("Gruppentypen", report.onlyForType != null ? String.join(", ", report.onlyForType) : "-")
                .build();

        Table.Builder parameterTable = new Table.Builder()
                .addRow("Name", "Beschreibung", "Bemerkung");

        report.parameter.forEach(param -> parameterTable.addRow(param.label, param.description, param.comment));

        Table.Builder versionTable = new Table.Builder()
                .addRow("Version", "Änderung", "Datum");
        report.versionHistory
                .forEach(version -> versionTable.addRow(version.version(), version.description, version.createdOn));

        // write Markdown file:
        try (Writer writer = new BufferedWriter(
                new FileWriter(outputDir.getPath() + File.separator + report.id + ".md"))) {

            writer
                    .append(new Heading(report.title, 1).toString()).append("\n")
                    .append(new Heading("Overview", 3).toString()).append("\n")
                    .append(overviewTable.toString()).append("\n")
                    .append(new Heading("Parameter", 3).toString()).append("\n")
                    .append(parameterTable.build().toString()).append("\n")
                    .append(new Heading("Versionsverlauf", 3).toString()).append("\n")
                    .append(versionTable.build().toString()).append("\n");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
