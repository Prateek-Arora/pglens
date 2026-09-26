package com.pglens.explain;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pglens.engine.model.QueryReport;
import com.pglens.engine.model.Recommendation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Loads the frozen eval cases ({@code docs/llm-eval.md}) as engine model objects. */
final class EvalCases {

  static final ObjectMapper JSON =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /** One case: its id, split, and the target the explainer is given. */
  record Case(String id, String split, ExplanationTarget target, JsonNode gold) {
    ExplanationFacts facts() {
      return FactsBuilder.build(target);
    }

    String ddl() {
      return target.recommendation().candidate().ddl();
    }
  }

  private EvalCases() {}

  static List<Case> all() {
    try (Stream<Path> files = Files.list(dir())) {
      JsonNode gold = read(dir().resolveSibling("gold.json")).get("cases");
      return files
          .filter(p -> p.toString().endsWith(".json"))
          .sorted()
          .map(p -> load(p, gold))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Case byId(String prefix) {
    return all().stream()
        .filter(c -> c.id().startsWith(prefix))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no eval case " + prefix));
  }

  private static Case load(Path file, JsonNode gold) {
    try {
      JsonNode root = read(file);
      JsonNode in = root.get("input");
      ExplanationTarget target =
          new ExplanationTarget(
              JSON.treeToValue(in.get("query"), QueryReport.class),
              JSON.treeToValue(in.get("recommendation"), Recommendation.class),
              in.get("otherQueriesCovered").asInt());
      String id = root.get("id").asText();
      return new Case(id, root.get("split").asText(), target, gold.get(id));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonNode read(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      return JSON.readTree(in);
    }
  }

  private static Path dir() {
    try {
      return Path.of(EvalCases.class.getResource("/eval/cases").toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
