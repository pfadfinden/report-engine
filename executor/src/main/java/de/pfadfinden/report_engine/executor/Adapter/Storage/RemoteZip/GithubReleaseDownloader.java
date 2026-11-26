package de.pfadfinden.report_engine.executor.Adapter.Storage.RemoteZip;

import de.pfadfinden.report_engine.executor.Observability.Logger;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads a GitHub release asset given its browser-style URL {@code
 * https://github.com/<owner>/<repo>/releases/download/<tag>/<file>}.
 *
 * <p>With a token (needed for private repos) that URL answers 404, so the asset is resolved via the
 * REST API instead: look up the release by tag, find the asset by file name, then download it from
 * the asset's API URL with {@code Accept: application/octet-stream}. Without a token, or for a URL
 * that isn't a GitHub release URL, the URL is fetched directly.
 *
 * <p>Redirects are followed by hand (the HTTP client doesn't follow them by default) so the token
 * is only ever sent to the host it was configured for, never to the signed storage URL GitHub
 * redirects to.
 *
 * <p>Every request has a connect and a response-header timeout, and transient failures (connect/
 * timeout errors, HTTP 408/429/502/503/504) are retried with jittered exponential backoff,
 * honouring {@code Retry-After}. Anything else - notably a 404 or 401 - fails immediately. Only the
 * request phase is retried; a connection dropped while the body streams fails the download (the
 * caller keeps its previous cached copy).
 */
public class GithubReleaseDownloader {

  private static final int MAX_REDIRECTS = 5;
  private static final String API_VERSION = "2022-11-28";

  private final String bearerToken;
  private final String apiBase;
  private final Pattern releaseUrl;
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 429, 502, 503, 504);
  private static final long MAX_DELAY_MS = 5_000;

  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  private final int maxAttempts;
  private final long baseDelayMs;

  /**
   * @param bearerToken GitHub token; null/blank for unauthenticated downloads.
   */
  public GithubReleaseDownloader(String bearerToken) {
    this(bearerToken, "https://github.com", "https://api.github.com", 3, 500);
  }

  /** Overridable hosts and retry policy, for tests. */
  GithubReleaseDownloader(
      String bearerToken, String webBase, String apiBase, int maxAttempts, long baseDelayMs) {
    this.maxAttempts = Math.max(1, maxAttempts);
    this.baseDelayMs = baseDelayMs;
    this.bearerToken = (bearerToken == null || bearerToken.isBlank()) ? null : bearerToken;
    this.apiBase = apiBase;
    this.releaseUrl =
        Pattern.compile(
            "^" + Pattern.quote(webBase) + "/([^/]+)/([^/]+)/releases/download/([^/]+)/([^/]+)$");
  }

  /** Downloads {@code url} into {@code destination} and returns the number of bytes written. */
  public long downloadTo(String url, File destination) throws IOException, InterruptedException {
    URI origin = URI.create(url);
    String accept = null;
    Matcher release = releaseUrl.matcher(url);
    if (bearerToken != null && release.matches()) {
      origin =
          resolveAssetApiUrl(
              release.group(1), release.group(2), release.group(3), release.group(4));
      accept = "application/octet-stream";
    }

    HttpResponse<InputStream> response = get(origin, accept);
    if (response.statusCode() != 200) {
      Logger.warn(
          "reports.download.failed",
          Attributes.of(
              AttributeKey.stringKey("reports.source_url"),
              url,
              AttributeKey.longKey("http.status_code"),
              (long) response.statusCode()));
      response.body().close();
      throw new IOException(
          "Failed to download reports bundle from " + url + ": HTTP " + response.statusCode());
    }
    try (InputStream in = response.body();
        OutputStream out = new FileOutputStream(destination)) {
      return in.transferTo(out);
    }
  }

  private URI resolveAssetApiUrl(String owner, String repo, String tag, String file)
      throws IOException, InterruptedException {
    URI releaseApi = URI.create(apiBase + "/repos/" + owner + "/" + repo + "/releases/tags/" + tag);
    HttpResponse<InputStream> response = get(releaseApi, "application/vnd.github+json");
    String body;
    try (InputStream in = response.body()) {
      body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    if (response.statusCode() != 200) {
      throw new IOException(
          "Failed to look up release "
              + tag
              + " of "
              + owner
              + "/"
              + repo
              + ": HTTP "
              + response.statusCode());
    }
    // Asset objects list "url" before "name"; no JSON library is available in this module.
    Matcher asset =
        Pattern.compile(
                "\"url\"\\s*:\\s*\"([^\"]*/releases/assets/\\d+)\"[^{}]*?\"name\"\\s*:\\s*\""
                    + Pattern.quote(file)
                    + "\"")
            .matcher(body);
    if (!asset.find()) {
      throw new IOException(
          "Release " + tag + " of " + owner + "/" + repo + " has no asset " + file);
    }
    return URI.create(asset.group(1));
  }

  /** Overload keeping the previous 3-arg test constructor usable with the default retry policy. */
  GithubReleaseDownloader(String bearerToken, String webBase, String apiBase) {
    this(bearerToken, webBase, apiBase, 3, 500);
  }

  private HttpResponse<InputStream> get(URI origin, String accept)
      throws IOException, InterruptedException {
    URI target = origin;
    HttpResponse<InputStream> response;
    int redirects = 0;
    while (true) {
      HttpRequest.Builder request = HttpRequest.newBuilder(target).GET();
      if (bearerToken != null && sameOrigin(origin, target)) {
        request.header("Authorization", "token " + bearerToken);
        request.header("X-GitHub-Api-Version", API_VERSION);
      }
      if (accept != null) {
        request.header("Accept", accept);
      }
      response = sendWithRetry(request.timeout(REQUEST_TIMEOUT).build());
      Optional<String> location = response.headers().firstValue("Location");
      if (response.statusCode() / 100 != 3 || location.isEmpty() || redirects++ >= MAX_REDIRECTS) {
        return response;
      }
      response.body().close();
      target = target.resolve(location.get());
    }
  }

  /**
   * One hop, retried while the failure looks transient; the last response/exception is surfaced.
   */
  private HttpResponse<InputStream> sendWithRetry(HttpRequest request)
      throws IOException, InterruptedException {
    for (int attempt = 1; ; attempt++) {
      HttpResponse<InputStream> response = null;
      IOException failure = null;
      try {
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (!RETRYABLE_STATUS.contains(response.statusCode())) {
          return response;
        }
      } catch (HttpTimeoutException | java.net.SocketException e) {
        failure = e;
      }

      long delayMs = backoffMs(attempt, response);
      if (attempt >= maxAttempts || delayMs > MAX_DELAY_MS) {
        // Out of attempts (or the server asked for a longer pause than is worth blocking for).
        if (response != null) {
          return response;
        }
        throw failure;
      }
      Logger.warn(
          "reports.download.retry",
          Attributes.of(
              AttributeKey.stringKey("http.url"),
              request.uri().getHost() + request.uri().getPath(),
              AttributeKey.longKey("attempt"),
              (long) attempt,
              AttributeKey.longKey("delay.ms"),
              delayMs,
              AttributeKey.longKey("http.status_code"),
              response == null ? -1L : (long) response.statusCode()));
      if (response != null) {
        response.body().close();
      }
      Thread.sleep(delayMs);
    }
  }

  /** Retry-After if the server sent one, else full-jitter exponential backoff. */
  private long backoffMs(int attempt, HttpResponse<?> response) {
    if (response != null) {
      Optional<String> retryAfter = response.headers().firstValue("Retry-After");
      if (retryAfter.isPresent()) {
        long seconds = parseRetryAfterSeconds(retryAfter.get());
        if (seconds >= 0) {
          return seconds * 1000;
        }
      }
    }
    long ceiling = Math.min(MAX_DELAY_MS, baseDelayMs << Math.min(attempt - 1, 10));
    return ceiling <= 0 ? 0 : ThreadLocalRandom.current().nextLong(ceiling + 1);
  }

  /** Delta-seconds or HTTP-date form; -1 if unparseable. */
  private static long parseRetryAfterSeconds(String value) {
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      try {
        long seconds =
            Duration.between(
                    ZonedDateTime.now(),
                    ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME))
                .getSeconds();
        return Math.max(0, seconds);
      } catch (RuntimeException ignored) {
        return -1;
      }
    }
  }

  private static boolean sameOrigin(URI a, URI b) {
    return a.getScheme().equalsIgnoreCase(b.getScheme())
        && a.getHost().equalsIgnoreCase(b.getHost())
        && a.getPort() == b.getPort();
  }
}
