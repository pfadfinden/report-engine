package de.pfadfinden.reports_engine.preprocessor.Tasks;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.stream.Stream;

import de.pfadfinden.reports_engine.preprocessor.AbstractFollowUpTaskCommand;
import de.pfadfinden.reports_engine.preprocessor.Metadata.ReportMetadata;
import net.steppschuh.markdowngenerator.table.Table;
import net.steppschuh.markdowngenerator.text.Text;
import net.steppschuh.markdowngenerator.text.heading.Heading;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "generate-md-overview")
public class GenerateMarkdownOverviewTask extends AbstractFollowUpTaskCommand {

    @Option(names = {
            "--fileName" }, defaultValue = "overview.md", description = "store the output in this file, including suffix.")
    private String fileName;

    public void export(Stream<ReportMetadata> reports) {
        Table.Builder reportsTable = new Table.Builder().addRow("ID", "Title");
        reports.forEachOrdered(report -> reportsTable.addRow(report.id, report.title));

        try (Writer writer = new BufferedWriter(
                new FileWriter(this.options.outputDir() + File.separator + this.fileName))) {

            writer
                    .append(new Heading("Reports Overview", 1).toString()).append("\n\n")
                    .append(new Text("Auto-generated overview of reports:").toString()).append("\n")
                    .append(reportsTable.build().toString()).append("\n");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
