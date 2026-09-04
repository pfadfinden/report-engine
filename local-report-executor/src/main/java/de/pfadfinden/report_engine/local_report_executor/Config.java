package de.pfadfinden.report_engine.local_report_executor;

import java.io.File;

public record Config(
    int port,
    String publicBaseUrl,
    File reportsDir,
    File sharedAssetsDir,
    File outputDir,
    String datasourceUrl,
    String downloadUrlSigningSecret,
    String apiKey) {

  public static Config fromEnv() {
    int port = intEnv("PORT", 8080);
    return new Config(
        port,
        stringEnv("PUBLIC_BASE_URL", "http://localhost:" + port),
        new File(stringEnv("REPORTS_DIR", "/reports")),
        // Assets common to every report (style template, fonts, logos) - a report
        // only needs its own copy of something here if it wants to override it.
        new File(stringEnv("SHARED_ASSETS_DIR", "/shared")),
        new File(stringEnv("OUTPUT_DIR", "/output")),
        requiredEnv("REPORT_SOURCEDATA_DATABASE_URL"),
        requiredEnv("DOWNLOAD_URL_SIGNING_SECRET"),
        requiredEnv("EXECUTOR_API_KEY"));
  }

  private static String stringEnv(String name, String fallback) {
    String value = System.getenv(name);
    return (value == null || value.isBlank()) ? fallback : value;
  }

  private static String requiredEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing required environment variable: " + name);
    }
    return value;
  }

  private static int intEnv(String name, int fallback) {
    String value = System.getenv(name);
    return (value == null || value.isBlank()) ? fallback : Integer.parseInt(value);
  }
}
