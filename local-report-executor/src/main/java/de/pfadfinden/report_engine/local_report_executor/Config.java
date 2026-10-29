package de.pfadfinden.report_engine.local_report_executor;

import java.io.File;
import java.time.Duration;

public record Config(
    int port,
    String publicBaseUrl,
    File reportsDir,
    File sharedAssetsDir,
    File outputDir,
    String datasourceUrl,
    String downloadUrlSigningSecret,
    String apiKey,
    String reportsReleaseUrl,
    String reportsToken,
    File reportsCacheDir,
    Duration reportsCacheTtl) {

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
        requiredEnv("EXECUTOR_API_KEY"),
        // Optional: when set, reports are fetched from <REPORTS_RELEASE_URL>/reports.zip (cached
        // under REPORTS_CACHE_DIR, refreshed after REPORTS_CACHE_TTL_MINUTES) instead of read
        // from REPORTS_DIR/SHARED_ASSETS_DIR. The frontend reads reports.db from the same release
        // with the same token and TTL - keep these three identical in both services.
        stringEnv("REPORTS_RELEASE_URL", null),
        // Secret: bearer token for the release assets, if they need one.
        stringEnv("REPORTS_TOKEN", null),
        new File(stringEnv("REPORTS_CACHE_DIR", "/tmp/reports-cache")),
        Duration.ofMinutes(intEnv("REPORTS_CACHE_TTL_MINUTES", 60)));
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
