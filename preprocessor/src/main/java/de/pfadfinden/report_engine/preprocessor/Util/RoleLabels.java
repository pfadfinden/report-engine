package de.pfadfinden.report_engine.preprocessor.Util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Display names of Hitobito role classes ("Group::Stamm::Stammesfuehrung") and group types
 * ("Group::Stamm"), taken from Hitobito's public API. Names are never derived from the class name:
 * without a label the class name itself is shown, and the miss is recorded in {@link #missing()} so
 * callers can warn about it.
 */
public class RoleLabels {

  private static final String SEPARATOR = "::";
  private static final int PAGE_SIZE = 1000;

  private final Map<String, String> roles;
  private final Map<String, String> groups;
  private final Set<String> missing = new TreeSet<>();

  public RoleLabels(Map<String, String> roles, Map<String, String> groups) {
    this.roles = Map.copyOf(roles);
    this.groups = Map.copyOf(groups);
  }

  public static RoleLabels none() {
    return new RoleLabels(Map.of(), Map.of());
  }

  /**
   * Loads the labels from the public (unauthenticated) JSON:API of a Hitobito instance: {@code
   * <apiBaseUrl>/role_types.jsonapi} and {@code <apiBaseUrl>/group_types.jsonapi}.
   *
   * @param apiBaseUrl e.g. https://next.mv.meinbdp.de/api
   */
  public static RoleLabels fromApi(String apiBaseUrl) {
    String base =
        apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    return new RoleLabels(
        fetchLabels(client, base + "/role_types.jsonapi"),
        fetchLabels(client, base + "/group_types.jsonapi"));
  }

  private static Map<String, String> fetchLabels(HttpClient client, String url) {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, String> labels = new HashMap<>();
    for (int page = 1; ; page++) {
      String pageUrl = url + "?page%5Bsize%5D=" + PAGE_SIZE + "&page%5Bnumber%5D=" + page;
      JsonNode data;
      try {
        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(URI.create(pageUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/vnd.api+json")
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
          throw new IOException("HTTP " + response.statusCode());
        }
        data = mapper.readTree(response.body()).path("data");
      } catch (IOException e) {
        throw new RuntimeException("Failed to load role labels from " + pageUrl, e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted while loading role labels from " + pageUrl, e);
      }
      data.forEach(item ->
          labels.put(item.path("id").asText(), item.path("attributes").path("label").asText()));
      if (data.size() < PAGE_SIZE) {
        return labels;
      }
    }
  }

  /** Label of a role class, or the class name if unknown. */
  public String role(String roleClass) {
    return lookup(roles, roleClass);
  }

  /** Label of the group type a role class belongs to, or the group's class name if unknown. */
  public String groupOf(String roleClass) {
    int end = roleClass.lastIndexOf(SEPARATOR);
    return end < 0 ? "" : lookup(groups, roleClass.substring(0, end));
  }

  /** Class names that had no label so far. */
  public Set<String> missing() {
    return new TreeSet<>(missing);
  }

  private String lookup(Map<String, String> labels, String key) {
    String label = labels.get(key);
    if (label == null || label.isBlank()) {
      missing.add(key);
      return key;
    }
    return label;
  }
}
