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
import java.util.Optional;
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
 */
public class GithubReleaseDownloader {

  private static final int MAX_REDIRECTS = 5;
  private static final String API_VERSION = "2022-11-28";

  private final String bearerToken;
  private final String apiBase;
  private final Pattern releaseUrl;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  /**
   * @param bearerToken GitHub token; null/blank for unauthenticated downloads.
   */
  public GithubReleaseDownloader(String bearerToken) {
    this(bearerToken, "https://github.com", "https://api.github.com");
  }

  /** Overridable hosts, for tests. */
  GithubReleaseDownloader(String bearerToken, String webBase, String apiBase) {
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
      response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      Optional<String> location = response.headers().firstValue("Location");
      if (response.statusCode() / 100 != 3 || location.isEmpty() || redirects++ >= MAX_REDIRECTS) {
        return response;
      }
      response.body().close();
      target = target.resolve(location.get());
    }
  }

  private static boolean sameOrigin(URI a, URI b) {
    return a.getScheme().equalsIgnoreCase(b.getScheme())
        && a.getHost().equalsIgnoreCase(b.getHost())
        && a.getPort() == b.getPort();
  }
}
