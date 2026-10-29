package de.pfadfinden.report_engine.executor.Adapter.Storage.RemoteZip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.pfadfinden.report_engine.executor.Exceptions.FailedToLoadReport;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteZipReportLoaderTest {

  private HttpServer server;
  private final AtomicReference<String> seenAuthorization = new AtomicReference<>();
  private int status = 200;

  @TempDir File cacheDir;

  @BeforeEach
  void startServer() throws Exception {
    byte[] zipBytes = zip();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/reports.zip",
        exchange -> {
          seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body = status == 200 ? zipBytes : new byte[0];
          exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
          if (body.length > 0) exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private static byte[] zip() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("dist/preprocessed/reports/"));
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  private String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/reports.zip";
  }

  @Test
  void sendsBearerTokenAndUnpacksBundle() {
    var loader = new RemoteZipReportLoader(url(), "s3cret", cacheDir, Duration.ofHours(1));

    // The bundle holds no such report, so loading fails after a successful download.
    assertThrows(FailedToLoadReport.class, () -> loader.load("missing"));

    assertEquals("Bearer s3cret", seenAuthorization.get());
    assertTrue(new File(cacheDir, "dist/preprocessed/reports").isDirectory());
  }

  @Test
  void sendsNoAuthorizationHeaderWithoutToken() {
    var loader = new RemoteZipReportLoader(url(), null, cacheDir, Duration.ofHours(1));

    assertThrows(FailedToLoadReport.class, () -> loader.load("missing"));

    assertEquals(null, seenAuthorization.get());
  }

  @Test
  void failedDownloadSurfacesAsFailedToLoadReport() {
    status = 401;
    var loader = new RemoteZipReportLoader(url(), "wrong", cacheDir, Duration.ofHours(1));

    assertThrows(FailedToLoadReport.class, () -> loader.load("any"));
  }
}
