package de.pfadfinden.report_engine.preprocessor.Tasks;

import de.pfadfinden.report_engine.preprocessor.AbstractFollowUpTaskCommand;
import de.pfadfinden.report_engine.preprocessor.Metadata.ParameterMetadata;
import de.pfadfinden.report_engine.preprocessor.Metadata.ReportMetadata;
import de.pfadfinden.report_engine.preprocessor.Metadata.VersionMetadata;
import de.pfadfinden.report_engine.preprocessor.Util.RoleLabels;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import net.steppschuh.markdowngenerator.table.Table;
import net.steppschuh.markdowngenerator.text.heading.Heading;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "generate-md-details-public")
public class GenerateMarkdownDetailsPublicTask extends AbstractFollowUpTaskCommand {
  protected static final String MAKRDOWN_FILE_SUFFIX = ".md";

  @Option(
      names = {"--role-labels-url"},
      description =
          "base URL of the JSON:API of a Hitobito instance (e.g. https://next.mv.meinbdp.de/api)"
              + " to take the display names of roles and groups from. Without it (or for roles it"
              + " does not know) the class names are shown as they are.")
  private String roleLabelsUrl;

  private RoleLabels roleLabels = RoleLabels.none();

  @Override
  public void run() {
    if (roleLabelsUrl != null) {
      roleLabels = RoleLabels.fromApi(roleLabelsUrl);
    }
    super.run();
    if (!roleLabels.missing().isEmpty()) {
      System.out.println("WARNING: no display name for roles/groups: " + roleLabels.missing());
    }
  }

  private static String toTableCell(String value) {
    return value == null ? null : value.replace("\r\n", "\n").replace("\n", "<br/>");
  }

  protected static final String GROUP_TYPE_PREFIX = "Group::";

  private static String stripGroupTypePrefix(String groupType) {
    return groupType.startsWith(GROUP_TYPE_PREFIX)
        ? groupType.substring(GROUP_TYPE_PREFIX.length())
        : groupType;
  }

  protected static final List<String> BASE_TAGS = List.of("bericht", "report", "auswertung");

  /**
   * Turns a category into a Confluence-safe label: lower case, umlauts transliterated, any run of
   * characters other than letters/digits collapsed into a single hyphen ("Ein-/Austritte" becomes
   * "ein-austritte").
   */
  static String toLabel(String value) {
    return value
        .toLowerCase(Locale.ROOT)
        .replace("ä", "ae")
        .replace("ö", "oe")
        .replace("ü", "ue")
        .replace("ß", "ss")
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("^-+|-+$", "");
  }

  /**
   * Info box for reports restricted to roles, with the (potentially long) role list collapsed and
   * grouped by group type. Empty if the report is not restricted.
   */
  static String roleRestriction(List<String> roles, RoleLabels labels) {
    if (roles == null || roles.isEmpty()) {
      return "";
    }
    Map<String, List<String>> byGroup = new LinkedHashMap<>();
    roles.forEach(role ->
        byGroup
            .computeIfAbsent(labels.groupOf(role), g -> new ArrayList<>())
            .add(labels.role(role)));
    StringBuilder md = new StringBuilder();
    md.append("!!! info \"Rechtebeschränkter Report\"\n")
        .append(
            "    Dieser Report ist nur für Personen mit bestimmten Rollen verfügbar. Die Rolle"
                + " muss in der gewählten Gruppe oder einer übergeordneten Gruppe bestehen.\n\n")
        .append("<details markdown=\"1\">\n<summary>Berechtigte Rollen (")
        .append(roles.size())
        .append(")</summary>\n\n");
    byGroup.forEach((group, names) ->
        md.append("- **")
            .append(group)
            .append(":** ")
            .append(String.join(", ", names))
            .append("\n"));
    md.append("\n</details>");
    return md.toString();
  }

  static String frontMatter(ReportMetadata report) {
    List<String> tags = new ArrayList<>();
    if (report.category != null && !toLabel(report.category).isEmpty()) {
      tags.add(toLabel(report.category));
    }
    tags.addAll(BASE_TAGS);
    if (report.onlyForType != null) {
      report.onlyForType.stream()
          .map(GenerateMarkdownDetailsPublicTask::stripGroupTypePrefix)
          .map(type -> "report-ebene-" + toLabel(type))
          .forEach(tags::add);
    }
    return "---\ntags: ["
        + tags.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(", "))
        + "]\n---\n";
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
              .addRow("Beschreibung", toTableCell(report.description))
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

      parameters.forEach(param ->
          parameterTable.addRow(
              param.label, toTableCell(param.description), toTableCell(param.comment)));

      Table.Builder versionTable = new Table.Builder().addRow("Version", "Änderung", "Datum");
      versionHistory.forEach(version ->
          versionTable.addRow(
              version.version(), toTableCell(version.description), version.createdOn));

      // write Markdown file:
      try (Writer writer =
          new BufferedWriter(
              new OutputStreamWriter(
                  new FileOutputStream(outputDir.getPath() + File.separator + report.id + ".md"),
                  StandardCharsets.UTF_8))) {

        // Blank lines between blocks are required: without them Markdown parsers treat the
        // following heading as part of the preceding table.
        writer
            .append(frontMatter(report))
            .append("\n")
            .append(new Heading(report.title, 1).toString())
            .append("\n\n")
            .append(new Heading("Übersicht", 3).toString())
            .append("\n\n")
            .append(overviewTable.toString())
            .append("\n\n")
            .append(
                report.onlyWithRole == null || report.onlyWithRole.isEmpty()
                    ? ""
                    : roleRestriction(report.onlyWithRole, roleLabels) + "\n\n")
            .append(new Heading("Parameter", 3).toString())
            .append("\n\n")
            .append(parameterTable.build().toString())
            .append("\n\n")
            .append(new Heading("Versionsverlauf", 3).toString())
            .append("\n\n")
            .append(versionTable.build().toString())
            .append("\n");
      }
    } catch (IOException | RuntimeException e) {
      e.printStackTrace();
      System.out.println("Failed to generate details page for report " + report.id);
    }
  }
}
