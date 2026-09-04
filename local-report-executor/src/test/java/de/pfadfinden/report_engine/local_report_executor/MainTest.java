package de.pfadfinden.report_engine.local_report_executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.pfadfinden.report_engine.executor.Port.ExecutionStatus;
import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import io.javalin.testtools.Response;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Exercises the app's routes and API-key middleware end to end via a real embedded server
 * (JavalinTest), rather than unit-testing the private handler methods in isolation - the middleware
 * wiring is exactly what's most worth covering here (see the security note in Main.createApp).
 */
class MainTest {

  private static final String API_KEY = "test-api-key";
  private static final String SIGNING_SECRET = "test-signing-secret";

  private Config config() {
    return new Config(
        0,
        "http://executor.internal",
        new File("."),
        new File("."),
        new File("."),
        "jdbc:postgresql://unused",
        SIGNING_SECRET,
        API_KEY);
  }

  @Test
  void reportsAndExecutionsRoutesRequireApiKey() {
    Javalin app =
        Main.createApp(
            config(),
            new ExecutionStore(),
            mock(ReportExecutionRunner.class),
            new DownloadUrlSigner(SIGNING_SECRET));

    JavalinTest.test(
        app,
        (server, client) -> {
          assertEquals(401, client.post("/reports/report-1/executions").code());
          assertEquals(401, client.get("/executions/exec-1/status").code());
          assertEquals(401, client.get("/executions/exec-1/download").code());
        });
  }

  @Test
  void triggerExecutionRecordsPendingAndDelegatesToRunner() {
    ExecutionStore store = new ExecutionStore();
    ReportExecutionRunner runner = mock(ReportExecutionRunner.class);
    Javalin app = Main.createApp(config(), store, runner, new DownloadUrlSigner(SIGNING_SECRET));

    JavalinTest.test(
        app,
        (server, client) -> {
          TriggerReportExecutionRequest requestBody =
              new TriggerReportExecutionRequest("exec-1", Map.of("year", 2026), "pdf");

          Response response =
              client.post(
                  "/reports/report-1/executions",
                  requestBody,
                  builder -> builder.header("Authorization", "Bearer " + API_KEY));

          assertEquals(202, response.code());
          assertNotNull(store.get("exec-1"));
          assertEquals(ExecutionStatus.PENDING, store.get("exec-1").status);
          verify(runner)
              .runAsync(eq("report-1"), eq("exec-1"), eq(Map.of("year", 2026)), eq("pdf"));
        });
  }

  @Test
  void getStatusReturnsNotFoundForUnknownExecution() {
    Javalin app =
        Main.createApp(
            config(),
            new ExecutionStore(),
            mock(ReportExecutionRunner.class),
            new DownloadUrlSigner(SIGNING_SECRET));

    JavalinTest.test(
        app,
        (server, client) -> {
          Response response =
              client.get(
                  "/executions/missing/status",
                  builder -> builder.header("Authorization", "Bearer " + API_KEY));

          assertEquals(404, response.code());
        });
  }

  @Test
  void getStatusReturnsCurrentStatusForKnownExecution() {
    ExecutionStore store = new ExecutionStore();
    store.createPending("exec-1");
    Javalin app =
        Main.createApp(
            config(),
            store,
            mock(ReportExecutionRunner.class),
            new DownloadUrlSigner(SIGNING_SECRET));

    JavalinTest.test(
        app,
        (server, client) -> {
          Response response =
              client.get(
                  "/executions/exec-1/status",
                  builder -> builder.header("Authorization", "Bearer " + API_KEY));

          assertEquals(200, response.code());
          assertTrue(response.body().string().contains("\"status\":\"PENDING\""));
        });
  }

  @Test
  void getDownloadUrlReturnsConflictWhenNotYetDone() {
    ExecutionStore store = new ExecutionStore();
    store.createPending("exec-1");
    Javalin app =
        Main.createApp(
            config(),
            store,
            mock(ReportExecutionRunner.class),
            new DownloadUrlSigner(SIGNING_SECRET));

    JavalinTest.test(
        app,
        (server, client) -> {
          Response response =
              client.get(
                  "/executions/exec-1/download",
                  builder -> builder.header("Authorization", "Bearer " + API_KEY));

          assertEquals(409, response.code());
          assertTrue(response.body().string().contains("\"status\":\"PENDING\""));
        });
  }

  @Test
  void getDownloadUrlReturnsSignedUrlWhenDone() {
    ExecutionStore store = new ExecutionStore();
    store.createPending("exec-1");
    store.get("exec-1").status = ExecutionStatus.DONE;
    Javalin app =
        Main.createApp(
            config(),
            store,
            mock(ReportExecutionRunner.class),
            new DownloadUrlSigner(SIGNING_SECRET));

    JavalinTest.test(
        app,
        (server, client) -> {
          Response response =
              client.get(
                  "/executions/exec-1/download",
                  builder -> builder.header("Authorization", "Bearer " + API_KEY));

          assertEquals(200, response.code());
          String body = response.body().string();
          assertTrue(body.contains("http://executor.internal/files/exec-1?expires="));
          assertTrue(body.contains("&sig="));
        });
  }

  @Test
  void filesRouteServesContentWithoutApiKeyWhenSignatureValid() throws IOException {
    File outputFile = Files.createTempFile("report", ".pdf").toFile();
    outputFile.deleteOnExit();
    Files.write(outputFile.toPath(), "fake pdf bytes".getBytes(StandardCharsets.UTF_8));

    ExecutionStore store = new ExecutionStore();
    store.createPending("exec-1");
    ExecutionState state = store.get("exec-1");
    state.status = ExecutionStatus.DONE;
    state.outputFile = outputFile;
    state.outputFormat = OutputFormat.PDF;

    DownloadUrlSigner signer = new DownloadUrlSigner(SIGNING_SECRET);
    long expires = Instant.now().plus(15, ChronoUnit.MINUTES).getEpochSecond();
    String signature = signer.sign("exec-1", expires);

    Javalin app = Main.createApp(config(), store, mock(ReportExecutionRunner.class), signer);

    JavalinTest.test(
        app,
        (server, client) -> {
          // Deliberately no Authorization header: /files/* is exempt from the API-key
          // middleware and relies solely on the signed URL below.
          Response response = client.get("/files/exec-1?expires=" + expires + "&sig=" + signature);

          assertEquals(200, response.code());
          assertEquals("application/pdf", response.headers().get("Content-Type").get(0));
          assertEquals("fake pdf bytes", response.body().string());
        });
  }

  @Test
  void filesRouteRejectsInvalidSignature() {
    Javalin app =
        Main.createApp(
            config(),
            new ExecutionStore(),
            mock(ReportExecutionRunner.class),
            new DownloadUrlSigner(SIGNING_SECRET));

    JavalinTest.test(
        app,
        (server, client) -> {
          long expires = Instant.now().plus(15, ChronoUnit.MINUTES).getEpochSecond();
          Response response =
              client.get("/files/exec-1?expires=" + expires + "&sig=not-a-real-signature");

          assertEquals(401, response.code());
        });
  }
}
