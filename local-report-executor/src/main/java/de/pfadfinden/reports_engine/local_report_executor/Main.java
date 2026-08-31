package de.pfadfinden.reports_engine.local_report_executor;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.pfadfinden.reports_engine.executor.Port.ExecutionStatus;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;

/**
 * Docker-based, HTTP report executor - exposes the same trigger/status/
 * download API contract as the Azure Functions executor, so the frontend can
 * be pointed at either one interchangeably.
 */
public class Main {

    private static final long DOWNLOAD_URL_TTL_SECONDS = 15 * 60;

    public static void main(String[] args) {
        Config config = Config.fromEnv();
        ExecutionStore executionStore = new ExecutionStore();
        ExecutorService backgroundExecutor = Executors.newFixedThreadPool(4);
        ReportExecutionRunner runner = new ReportExecutionRunner(config, executionStore, backgroundExecutor);
        DownloadUrlSigner signer = new DownloadUrlSigner(config.downloadUrlSigningSecret());

        Javalin app = createApp(config, executionStore, runner, signer);
        app.start(config.port());
    }

    /** Package-private so tests can build and exercise the app without binding a real port. */
    static Javalin createApp(Config config, ExecutionStore executionStore, ReportExecutionRunner runner,
            DownloadUrlSigner signer) {
        Javalin app = Javalin.create();

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
        app.before("/reports/*", ctx -> requireApiKey(ctx, config.apiKey()));
        app.before("/executions/*", ctx -> requireApiKey(ctx, config.apiKey()));

        app.post("/reports/{reportId}/executions", ctx -> triggerExecution(ctx, executionStore, runner));
        app.get("/executions/{executionId}/status", ctx -> getStatus(ctx, executionStore));
        app.get("/executions/{executionId}/download", ctx -> getDownloadUrl(ctx, executionStore, signer, config));
        app.get("/files/{executionId}", ctx -> serveFile(ctx, executionStore, signer));

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

    private static void triggerExecution(Context ctx, ExecutionStore executionStore, ReportExecutionRunner runner) {
        String reportId = ctx.pathParam("reportId");
        TriggerReportExecutionRequest request = ctx.bodyAsClass(TriggerReportExecutionRequest.class);

        executionStore.createPending(request.executionId());
        runner.runAsync(reportId, request.executionId(), request.parameter(), request.outputFormat());

        ctx.status(202);
    }

    private static void getStatus(Context ctx, ExecutionStore executionStore) {
        ExecutionState state = executionStore.get(ctx.pathParam("executionId"));
        if (state == null) {
            ctx.status(404);
            return;
        }
        ctx.json(Map.of("status", state.status.name()));
    }

    private static void getDownloadUrl(Context ctx, ExecutionStore executionStore, DownloadUrlSigner signer,
            Config config) {
        String executionId = ctx.pathParam("executionId");
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

        long expires = Instant.now().plus(DOWNLOAD_URL_TTL_SECONDS, ChronoUnit.SECONDS).getEpochSecond();
        String signature = signer.sign(executionId, expires);
        String url = "%s/files/%s?expires=%d&sig=%s".formatted(config.publicBaseUrl(), executionId, expires,
                signature);

        ctx.json(Map.of("url", url));
    }

    private static void serveFile(Context ctx, ExecutionStore executionStore, DownloadUrlSigner signer) {
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

        try {
            ctx.contentType(state.outputFormat.contentType());
            ctx.header("Content-Disposition", "attachment; filename=\"" + state.outputFile.getName() + "\"");
            ctx.result(new FileInputStream(state.outputFile));
        } catch (FileNotFoundException e) {
            ctx.status(404);
        }
    }
}
