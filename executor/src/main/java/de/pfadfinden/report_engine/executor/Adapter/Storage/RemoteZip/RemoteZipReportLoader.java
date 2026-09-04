package de.pfadfinden.report_engine.executor.Adapter.Storage.RemoteZip;

import de.pfadfinden.report_engine.executor.Adapter.Storage.Filesystem.LocalFilesystemReportLoader;
import de.pfadfinden.report_engine.executor.Exceptions.FailedToLoadReport;
import de.pfadfinden.report_engine.executor.Port.ReportDefinition;
import de.pfadfinden.report_engine.executor.Port.ReportLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads and unzips a released reports bundle (e.g. mv_reports' CI "reports.zip" artifact) into
 * a local cache directory, refreshing it once it's older than the configured TTL, then delegates
 * the actual report/jrtx loading to LocalFilesystemReportLoader against the unzipped directory -
 * mirrors the TTL-cache pattern the frontend already uses for its own remote metadata DB.
 */
public class RemoteZipReportLoader implements ReportLoader {

  private final String reportsZipUrl;
  private final File cacheDir;
  private final Duration ttl;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  public RemoteZipReportLoader(String reportsZipUrl, File cacheDir, Duration ttl) {
    this.reportsZipUrl = reportsZipUrl;
    this.cacheDir = cacheDir;
    this.ttl = ttl;
  }

  @Override
  public synchronized ReportDefinition load(String reportName) throws FailedToLoadReport {
    try {
      ensureFreshCache();
    } catch (IOException | InterruptedException e) {
      throw new FailedToLoadReport(e);
    }
    // The zip is built from mv_reports' own dist/preprocessed output (see its CI
    // workflow), so it preserves that path prefix: dist/preprocessed/reports/<id>/
    // for compiled reports, dist/preprocessed/_shared/ for assets common to every
    // report (style template, fonts, logos).
    File reportsDir = new File(cacheDir, "dist/preprocessed/reports");
    File sharedAssetsDir = new File(cacheDir, "dist/preprocessed/_shared");
    return new LocalFilesystemReportLoader(reportsDir, sharedAssetsDir).load(reportName);
  }

  private void ensureFreshCache() throws IOException, InterruptedException {
    File marker = new File(cacheDir, ".downloaded-at");
    if (marker.isFile()) {
      Instant downloadedAt = Instant.ofEpochMilli(marker.lastModified());
      if (Instant.now().isBefore(downloadedAt.plus(ttl))) {
        return;
      }
    }

    File zipFile = File.createTempFile("reports", ".zip");
    try {
      downloadTo(zipFile);

      if (cacheDir.exists()) {
        deleteRecursively(cacheDir.toPath());
      }
      cacheDir.mkdirs();
      unzip(zipFile, cacheDir);

      marker.createNewFile();
    } finally {
      zipFile.delete();
    }
  }

  private void downloadTo(File destination) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(reportsZipUrl)).GET().build();
    HttpResponse<InputStream> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) {
      throw new IOException(
          "Failed to download reports bundle from "
              + reportsZipUrl
              + ": HTTP "
              + response.statusCode());
    }
    try (InputStream in = response.body();
        OutputStream out = new FileOutputStream(destination)) {
      in.transferTo(out);
    }
  }

  private void deleteRecursively(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    }
  }

  private void unzip(File zipFile, File targetDir) throws IOException {
    Path canonicalTargetDir = targetDir.getCanonicalFile().toPath();
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile.toPath()))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        File entryFile = new File(targetDir, entry.getName());
        // Zip Slip guard: a malicious/compromised bundle could otherwise use an entry name
        // like "../../etc/cron.d/x" to write outside targetDir.
        Path canonicalEntryPath = entryFile.getCanonicalFile().toPath();
        if (!canonicalEntryPath.startsWith(canonicalTargetDir)) {
          throw new IOException("Zip entry escapes target directory: " + entry.getName());
        }
        if (entry.isDirectory()) {
          entryFile.mkdirs();
        } else {
          entryFile.getParentFile().mkdirs();
          try (OutputStream out = new FileOutputStream(entryFile)) {
            zip.transferTo(out);
          }
        }
      }
    }
  }
}
