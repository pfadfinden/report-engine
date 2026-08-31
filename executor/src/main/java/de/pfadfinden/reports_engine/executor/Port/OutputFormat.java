package de.pfadfinden.reports_engine.executor.Port;

/**
 * Output formats FillReportService knows how to export a filled report to. The value() matches what
 * report metadata.yaml's outputFormats: list and the frontend's format picker use verbatim (e.g.
 * "pdf", "xlsx"), so a caller-selected format round-trips through the HTTP API with no translation
 * layer.
 */
public enum OutputFormat {
  PDF("pdf", "application/pdf"),
  XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private final String value;
  private final String contentType;

  OutputFormat(String value, String contentType) {
    this.value = value;
    this.contentType = contentType;
  }

  public String value() {
    return value;
  }

  public String contentType() {
    return contentType;
  }

  public static OutputFormat fromValue(String value) {
    for (OutputFormat format : values()) {
      if (format.value.equalsIgnoreCase(value)) {
        return format;
      }
    }
    throw new IllegalArgumentException("Unsupported output format: " + value);
  }
}
