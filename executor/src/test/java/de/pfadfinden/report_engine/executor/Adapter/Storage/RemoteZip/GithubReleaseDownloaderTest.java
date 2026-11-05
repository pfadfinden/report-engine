package de.pfadfinden.report_engine.executor.Adapter.Storage.RemoteZip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GithubReleaseDownloaderTest {

  private HttpServer server;
  private String base;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final byte[] asset = "ASSET-BYTES".getBytes(StandardCharsets.UTF_8);

  @TempDir File tmp;

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/repos/o/r/releases/tags/v1",
        ex -> {
          record(ex);
          String json =
              "{\"assets\":[{\"url\":\""
                  + base
                  + "/repos/o/r/releases/assets/7\",\"id\":7,\"name\":\"other.zip\"},"
                  + "{\"url\":\""
                  + base
                  + "/repos/o/r/releases/assets/42\",\"id\":42,\"name\":\"reports.zip\","
                  + "\"uploader\":{\"login\":\"x\"}}]}";
          respond(ex, 200, json.getBytes(StandardCharsets.UTF_8));
        });
    server.createContext(
        "/repos/o/r/releases/assets/42",
        ex -> {
          record(ex);
          ex.getResponseHeaders()
              .add(
                  "Location",
                  "http://localhost:" + server.getAddress().getPort() + "/storage/blob");
          ex.sendResponseHeaders(302, -1);
          ex.close();
        });
    server.createContext(
        "/storage/blob",
        ex -> {
          record(ex);
          respond(ex, 200, asset);
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private void record(HttpExchange ex) {
    requests.add(
        ex.getRequestURI().getPath()
            + " auth="
            + ex.getRequestHeaders().getFirst("Authorization")
            + " accept="
            + ex.getRequestHeaders().getFirst("Accept"));
  }

  private static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
    ex.sendResponseHeaders(status, body.length);
    ex.getResponseBody().write(body);
    ex.close();
  }

  @Test
  void resolvesAssetViaApiThenDownloadsItWithoutLeakingTokenToRedirectTarget() throws Exception {
    var downloader = new GithubReleaseDownloader("tok", base, base);
    File out = new File(tmp, "out.zip");

    downloader.downloadTo(base + "/o/r/releases/download/v1/reports.zip", out);

    assertArrayEquals(asset, Files.readAllBytes(out.toPath()));
    assertEquals(
        List.of(
            "/repos/o/r/releases/tags/v1 auth=token tok accept=application/vnd.github+json",
            "/repos/o/r/releases/assets/42 auth=token tok accept=application/octet-stream",
            "/storage/blob auth=null accept=application/octet-stream"),
        requests.stream().map(r -> r.replaceAll("(127\\.0\\.0\\.1|localhost):\\d+", "")).toList());
  }

  @Test
  void failsWhenReleaseHasNoMatchingAsset() {
    var downloader = new GithubReleaseDownloader("tok", base, base);

    assertThrows(
        IOException.class,
        () ->
            downloader.downloadTo(
                base + "/o/r/releases/download/v1/missing.zip", new File(tmp, "x.zip")));
  }
}
