package de.pfadfinden.report_engine.preprocessor.Tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.pfadfinden.report_engine.preprocessor.Metadata.ReportMetadata;
import de.pfadfinden.report_engine.preprocessor.Metadata.VersionMetadata;
import de.pfadfinden.report_engine.preprocessor.Util.RoleLabels;
import org.junit.jupiter.api.Test;

class GenerateMarkdownDetailsPublicTaskTest {

  @Test
  void labelsAreConfluenceSafe() {
    assertEquals("ein-austritte", GenerateMarkdownDetailsPublicTask.toLabel("Ein-/Austritte"));
    assertEquals(
        "funktionstraeger-innen",
        GenerateMarkdownDetailsPublicTask.toLabel("Funktionsträger*innen"));
    assertEquals(
        "plausibilitaets-pruefungen",
        GenerateMarkdownDetailsPublicTask.toLabel("Plausibilitäts-Prüfungen"));
  }

  @Test
  void frontMatterContainsCategoryAndBaseTags() {
    ReportMetadata r = new ReportMetadata();
    r.category = "Statistiken";
    assertEquals(
        "---\ntags: [\"statistiken\", \"bericht\", \"report\", \"auswertung\"]\n---\n",
        GenerateMarkdownDetailsPublicTask.frontMatter(r));
    r.category = "";
    assertEquals(
        "---\ntags: [\"bericht\", \"report\", \"auswertung\"]\n---\n",
        GenerateMarkdownDetailsPublicTask.frontMatter(r));
  }

  @Test
  void frontMatterContainsLayerTags() {
    ReportMetadata r = new ReportMetadata();
    r.category = "Statistiken";
    r.onlyForType = java.util.List.of("Group::Bundesebene", "Group::Stamm");
    assertEquals(
        "---\ntags: [\"statistiken\", \"bericht\", \"report\", \"auswertung\","
            + " \"report-ebene-bundesebene\", \"report-ebene-stamm\"]\n---\n",
        GenerateMarkdownDetailsPublicTask.frontMatter(r));
  }

  @Test
  void versionHistoryIsSortedNewestFirst() {
    java.util.function.BiFunction<String, String, VersionMetadata> v =
        (version, date) -> {
          VersionMetadata m = new VersionMetadata();
          m.setVersion(version);
          m.createdOn = java.time.LocalDate.parse(date);
          return m;
        };
    java.util.List<VersionMetadata> sorted =
        GenerateMarkdownDetailsPublicTask.newestFirst(
            java.util.List.of(
                v.apply("1.0", "2024-01-01"),
                v.apply("1.2", "2025-03-01"),
                v.apply("1.1", "2024-06-01")));
    assertEquals(
        java.util.List.of("1.2", "1.1", "1.0"),
        sorted.stream().map(VersionMetadata::version).toList());
  }

  @Test
  void roleRestrictionIsCollapsedAndGroupedByGroupType() {
    RoleLabels labels =
        new RoleLabels(
            java.util.Map.of(
                "Group::Stamm::Stammesfuehrung", "Stammesführer*in",
                "Group::Stamm::StammesfuehrungStv", "Stv. Stammesführer*in"),
            java.util.Map.of("Group::Stamm", "Stamm"));
    String md =
        GenerateMarkdownDetailsPublicTask.roleRestriction(
            java.util.List.of(
                "Group::Stamm::Stammesfuehrung",
                "Group::Stamm::StammesfuehrungStv",
                "Group::Bund::Unbekannt"),
            labels);
    assertTrue(md.startsWith("!!! info \"Rechtebeschränkter Report\""));
    assertTrue(md.contains("<summary>Berechtigte Rollen (3)</summary>"));
    assertTrue(md.contains("- **Stamm:** Stammesführer\\*in, Stv. Stammesführer\\*in"));
    // never guessed: unknown names are shown as the class name and reported
    assertTrue(md.contains("- **Group::Bund:** Group::Bund::Unbekannt"));
    assertEquals(java.util.Set.of("Group::Bund::Unbekannt", "Group::Bund"), labels.missing());
    assertEquals("", GenerateMarkdownDetailsPublicTask.roleRestriction(null, labels));
  }

  @Test
  void roleLabelsAreLoadedFromTheApi() throws Exception {
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/role_types.jsonapi",
        exchange ->
            respond(
                exchange,
                "{\"data\":[{\"id\":\"Group::Stamm::Stammesfuehrung\",\"attributes\":{\"label\":\"Stammesführer*in\"}}]}"));
    server.createContext(
        "/api/group_types.jsonapi",
        exchange ->
            respond(
                exchange,
                "{\"data\":[{\"id\":\"Group::Stamm\",\"attributes\":{\"label\":\"Stamm\"}}]}"));
    server.start();
    try {
      RoleLabels labels =
          RoleLabels.fromApi("http://127.0.0.1:" + server.getAddress().getPort() + "/api/");
      assertEquals("Stammesführer*in", labels.role("Group::Stamm::Stammesfuehrung"));
      assertEquals("Stamm", labels.groupOf("Group::Stamm::Stammesfuehrung"));
    } finally {
      server.stop(0);
    }
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
      throws java.io.IOException {
    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
