package de.pfadfinden.report_engine.local_report_executor;

import de.pfadfinden.report_engine.executor.Observability.AuditAttributes;
import de.pfadfinden.report_engine.executor.Observability.Logger;
import de.pfadfinden.report_engine.executor.Observability.ReportMetrics;
import de.pfadfinden.report_engine.executor.Observability.TraceContextPropagation;
import de.pfadfinden.report_engine.executor.Port.ExecutionIdFormat;
import de.pfadfinden.report_engine.executor.Port.ExecutionStatus;
import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Docker-based, HTTP report executor - exposes the same trigger/status/ download API contract as
 * the Azure Functions executor, so the frontend can be pointed at either one interchangeably.
 */
public class Main {

  private static final long DOWNLOAD_URL_TTL_SECONDS = 15 * 60;

  public static void main(String[] args) {
    Telemetry.init();
    Config config = Config.fromEnv();
    ExecutionStore executionStore = new ExecutionStore();
    // Wrapped so a task submitted here captures whatever OTel context is active at submission
    // time (report.trigger's, in particular) and restores it on the worker thread - otherwise
    // report.execution would start as a disconnected new trace instead of a child span, since
    // plain ExecutorService.submit() doesn't carry context across threads on its own.
    ExecutorService backgroundExecutor =
        io.opentelemetry.context.Context.taskWrapping(Executors.newFixedThreadPool(4));
    ReportExecutionRunner runner =
        new ReportExecutionRunner(config, executionStore, backgroundExecutor);
    DownloadUrlSigner signer = new DownloadUrlSigner(config.downloadUrlSigningSecret());

    Javalin app = createApp(config, executionStore, runner, signer);
    app.start(config.port());
  }

  /** Package-private so tests can build and exercise the app without binding a real port. */
  static Javalin createApp(
      Config config,
      ExecutionStore executionStore,
      ReportExecutionRunner runner,
      DownloadUrlSigner signer) {
    Javalin app =
        Javalin.create(
            javalinConfig -> {
              javalinConfig.showJavalinBanner = false;

              // This service trusts its caller completely: it has no notion of which
              // reports/parameters a given end-user is allowed to request, that
              // authorization decision is made entirely by the frontend before it
              // calls here. It must never be reachable by anything other than the
              // frontend backend - not by end-user browsers, not on a public or
              // shared network. This check is defense-in-depth for that boundary,
              // not a substitute for keeping this service off any network an
              // untrusted caller could reach.
              //
              // /files/* is deliberately exempt: it's protected by its own signed,
              // short-lived, single-purpose URL (see serveFile) instead, mirroring
              // how the Azure executor's equivalent is a SAS blob URL - neither
              // needs the API key on top, and this keeps the two backends'
              // returned download URLs interchangeable for the frontend proxy.
              javalinConfig.routes.before("/reports/*", ctx -> requireApiKey(ctx, config.apiKey()));
              javalinConfig.routes.before(
                  "/executions/*", ctx -> requireApiKey(ctx, config.apiKey()));

              javalinConfig.routes.get("/healthz", ctx -> ctx.status(200));
              javalinConfig.routes.post(
                  "/reports/{reportId}/executions",
                  ctx -> triggerExecution(ctx, executionStore, runner));
              javalinConfig.routes.get(
                  "/executions/{executionId}/status", ctx -> getStatus(ctx, executionStore));
              javalinConfig.routes.get(
                  "/executions/{executionId}/download",
                  ctx -> getDownloadUrl(ctx, executionStore, signer, config));
              javalinConfig.routes.get(
                  "/files/{executionId}", ctx -> serveFile(ctx, executionStore, signer));
            });

    return app;
  }

  private static void requireApiKey(Context ctx, String apiKey) {
    String header = ctx.header("Authorization");
    String presented = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    if (presented == null || !constantTimeEquals(presented, apiKey)) {
      throw new UnauthorizedResponse();
    }
  }

  private static boolean constantTimeEquals(String a, String b) {
    byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
    byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
    if (aBytes.length != bBytes.length) {
      return false;
    }
    int result = 0;
    for (int i = 0; i < aBytes.length; i++) {
      result |= aBytes[i] ^ bBytes[i];
    }
    return result == 0;
  }

  private static void triggerExecution(
      Context ctx, ExecutionStore executionStore, ReportExecutionRunner runner) {
    String reportId = ctx.pathParam("reportId");
    TriggerReportExecutionRequest request = ctx.bodyAsClass(TriggerReportExecutionRequest.class);

    // Validated synchronously rather than left for the background fill to discover: without
    // this, an unsupported outputFormat still returns 202 here and only surfaces later as an
    // async FAILED status, instead of an immediate, actionable 400.
    try {
      OutputFormat.fromValue(request.outputFormat());
    } catch (IllegalArgumentException e) {
      ctx.status(400).result("Unsupported outputFormat: " + request.outputFormat());
      return;
    }

    // executionId is used unsanitized as part of the output file's path (see
    // ReportExecutionRunner) - rejecting anything that isn't a well-formed UUID/ULID here stops
    // a crafted id (e.g. containing "../") from escaping OUTPUT_DIR.
    if (!ExecutionIdFormat.isValid(request.executionId())) {
      ctx.status(400).result("executionId must be a valid UUID or ULID");
      return;
    }

    Span span =
        tracer()
            .spanBuilder("report.trigger")
            .setParent(TraceContextPropagation.extract(ctx.headerMap()))
            .setAttribute("report.id", reportId)
            .setAttribute("execution.id", request.executionId())
            .setAttribute("output.format", request.outputFormat())
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
      executionStore.createPending(request.executionId(), reportId);
      runner.runAsync(reportId, request.executionId(), request.parameter(), request.outputFormat());

      AttributesBuilder attributes =
          Attributes.builder()
              .put("report.id", reportId)
              .put("execution.id", request.executionId())
              .put("output.format", request.outputFormat());
      AuditAttributes.extractGroupId(request.parameter())
          .ifPresent(groupId -> attributes.put("group.id", groupId));
      Logger.event("report.trigger.requested", attributes.build());
      ReportMetrics.recordTrigger(reportId);

      ctx.status(202);
    } finally {
      span.end();
    }
  }

  /**
   * Fetched fresh on every call rather than cached in a static field: a Tracer built against a
   * not-yet-registered (no-op) OpenTelemetry instance stays bound to it forever, and this makes the
   * correctness of instrumentation independent of exactly when Telemetry.init() runs relative to
   * this class loading.
   */
  private static Tracer tracer() {
    return GlobalOpenTelemetry.getTracer("de.pfadfinden.report_engine.local_report_executor");
  }

  private static void getStatus(Context ctx, ExecutionStore executionStore) {
    String executionId = ctx.pathParam("executionId");
    if (!ExecutionIdFormat.isValid(executionId)) {
      ctx.status(400);
      return;
    }
    Span span =
        tracer()
            .spanBuilder("report.status")
            .setParent(TraceContextPropagation.extract(ctx.headerMap()))
            .setAttribute("execution.id", executionId)
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
      ExecutionState state = executionStore.get(executionId);
      if (state == null) {
        ctx.status(404);
        return;
      }
      span.setAttribute("status", state.status.name());
      ctx.json(Map.of("status", state.status.name()));
    } finally {
      span.end();
    }
  }

  private static void getDownloadUrl(
      Context ctx, ExecutionStore executionStore, DownloadUrlSigner signer, Config config) {
    String executionId = ctx.pathParam("executionId");
    if (!ExecutionIdFormat.isValid(executionId)) {
      ctx.status(400);
      return;
    }
    Span span =
        tracer()
            .spanBuilder("report.download.url_issued")
            .setParent(TraceContextPropagation.extract(ctx.headerMap()))
            .setAttribute("execution.id", executionId)
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
      ExecutionState state = executionStore.get(executionId);
      if (state == null) {
        ctx.status(404);
        return;
      }
      if (state.status != ExecutionStatus.DONE) {
        ctx.status(409);
        ctx.json(Map.of("status", state.status.name()));
        return;
      }

      long expires =
          Instant.now().plus(DOWNLOAD_URL_TTL_SECONDS, ChronoUnit.SECONDS).getEpochSecond();
      String signature = signer.sign(executionId, expires);
      String url =
          "%s/files/%s?expires=%d&sig=%s"
              .formatted(config.publicBaseUrl(), executionId, expires, signature);

      ctx.json(Map.of("url", url));
    } finally {
      span.end();
    }
  }

  private static void serveFile(
      Context ctx, ExecutionStore executionStore, DownloadUrlSigner signer) {
    String executionId = ctx.pathParam("executionId");
    long expires = Long.parseLong(ctx.queryParam("expires"));
    String signature = ctx.queryParam("sig");

    if (signature == null || !signer.isValid(executionId, expires, signature)) {
      ctx.status(401);
      return;
    }

    ExecutionState state = executionStore.get(executionId);
    if (state == null || state.status != ExecutionStatus.DONE || state.outputFile == null) {
      ctx.status(404);
      return;
    }

    Span span =
        tracer()
            .spanBuilder("report.download")
            .setParent(TraceContextPropagation.extract(ctx.headerMap()))
            .setAttribute("report.id", state.reportId)
            .setAttribute("execution.id", executionId)
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
      File outputFile = state.outputFile;
      long size = outputFile.length();
      span.setAttribute(AttributeKey.longKey("output.size_bytes"), size);

      ctx.contentType(state.outputFormat.contentType());
      ctx.header("Content-Disposition", "attachment; filename=\"" + outputFile.getName() + "\"");
      ctx.result(new FileInputStream(outputFile));

      Logger.event(
          "report.download",
          Attributes.builder()
              .put("report.id", state.reportId)
              .put("execution.id", executionId)
              .put(AttributeKey.longKey("output.size_bytes"), size)
              .build());
      ReportMetrics.recordDownload(state.reportId);
    } catch (FileNotFoundException e) {
      span.recordException(e);
      ctx.status(404);
    } finally {
      span.end();
    }
  }
}
