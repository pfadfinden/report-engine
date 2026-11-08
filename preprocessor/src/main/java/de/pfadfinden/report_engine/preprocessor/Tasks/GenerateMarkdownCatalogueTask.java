package de.pfadfinden.report_engine.preprocessor.Tasks;

import de.pfadfinden.report_engine.preprocessor.AbstractFollowUpTaskCommand;
import de.pfadfinden.report_engine.preprocessor.Metadata.ParameterMetadata;
import de.pfadfinden.report_engine.preprocessor.Metadata.ReportMetadata;
import de.pfadfinden.report_engine.preprocessor.Metadata.VersionMetadata;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Generates a single Markdown catalogue of all reports (docs/Reports.md in mv_reports): one section
 * per {@code category}, one table row per report.
 */
@Command(name = "generate-md-catalogue")
public class GenerateMarkdownCatalogueTask extends AbstractFollowUpTaskCommand {

  static final String UNCATEGORIZED = "Weitere Reports";
  private static final String GROUP_TYPE_PREFIX = "Group::";
  private static final String GROUP_ID_PARAMETER = "p_gruppe_id";
  private static final String EMPTY = "–";

  @Option(
      names = {"--fileName"},
      defaultValue = "Reports.md",
      description = "store the output in this file (relative to the output dir), including suffix.")
  private String fileName;

  @Option(
      names = {"--category-order"},
      split = ",",
      description =
          "comma separated category names in the order they should appear; remaining categories"
              + " follow alphabetically.")
  private List<String> categoryOrder = List.of();

  @Override
  public void export(Stream<ReportMetadata> reports) {
    Path target = this.options.outputDir().toPath().resolve(this.fileName);

    try {
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(target, render(reports.toList()), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to generate reports catalogue", e);
    }
  }

  String render(List<ReportMetadata> reports) {
    Map<String, List<ReportMetadata>> byCategory =
        reports.stream()
            .sorted(Comparator.comparing(r -> Objects.toString(r.title, r.id).toLowerCase()))
            .collect(
                Collectors.groupingBy(
                    r -> isBlank(r.category) ? UNCATEGORIZED : r.category.trim(),
                    LinkedHashMap::new,
                    Collectors.toList()));

    List<String> categories = new ArrayList<>(byCategory.keySet());
    categories.sort(
        Comparator.comparingInt(this::categoryRank)
            .thenComparing(c -> c.equals(UNCATEGORIZED))
            .thenComparing(Comparator.naturalOrder()));

    StringBuilder md = new StringBuilder();
    md.append("# Reports\n\n")
        .append("Übersicht aller Reports in diesem Repository, erzeugt aus den `metadata.yaml`.\n")
        .append(
            "„Ebenen“ entspricht `onlyForType`: Nur für Gruppen dieser Typen bietet das Frontend"
                + " den Report an.\n")
        .append(
            "Die Gruppierung (`p_gruppe_id`) füllt das Frontend automatisch; aufgeführt sind die"
                + " übrigen Parameter.\n");

    for (String category : categories) {
      md.append("\n## ").append(category).append("\n\n");
      md.append("| Report | Beschreibung | Version | Format | Ebenen | Parameter |\n");
      md.append("|---|---|---|---|---|---|\n");
      byCategory.get(category).forEach(report -> md.append(row(report)).append('\n'));
    }
    return md.toString();
  }

  private int categoryRank(String category) {
    int index = categoryOrder.indexOf(category);
    return index < 0 ? Integer.MAX_VALUE : index;
  }

  private static String row(ReportMetadata report) {
    return "| "
        + String.join(
            " | ",
            "**" + cell(report.title) + "**<br>`" + report.id + "`",
            cell(report.description),
            cell(latestVersion(report)),
            cell(join(report.outputFormats, ", ")),
            cell(levels(report)),
            cell(parameters(report)))
        + " |";
  }

  private static String latestVersion(ReportMetadata report) {
    if (report.versionHistory == null || report.versionHistory.isEmpty()) {
      return "";
    }
    VersionMetadata latest = report.versionHistory.get(report.versionHistory.size() - 1);
    return latest.version();
  }

  private static String levels(ReportMetadata report) {
    if (report.onlyForType == null || report.onlyForType.isEmpty()) {
      return "alle";
    }
    return report.onlyForType.stream()
        .map(t -> t.startsWith(GROUP_TYPE_PREFIX) ? t.substring(GROUP_TYPE_PREFIX.length()) : t)
        .map(GenerateMarkdownCatalogueTask::levelName)
        .collect(Collectors.joining(", "));
  }

  private static String levelName(String groupType) {
    return switch (groupType) {
      case "Bundesebene" -> "Bund";
      case "Landesverband" -> "Land";
      default -> groupType;
    };
  }

  private static String parameters(ReportMetadata report) {
    if (report.parameter == null) {
      return "";
    }
    return report.parameter.stream()
        .filter(p -> !GROUP_ID_PARAMETER.equals(p.name()))
        .map(GenerateMarkdownCatalogueTask::parameterName)
        .collect(Collectors.joining(", "));
  }

  private static String parameterName(ParameterMetadata parameter) {
    return isBlank(parameter.label) ? parameter.name() : parameter.label;
  }

  private static String join(List<String> values, String separator) {
    return values == null ? "" : String.join(separator, values);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** Makes a value safe for a Markdown table cell; empty values render as a dash. */
  private static String cell(String value) {
    if (isBlank(value)) {
      return EMPTY;
    }
    return value.trim().replace("|", "\\|").replace("\r\n", "\n").replace("\n", "<br>");
  }
}
