package com.pglens.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pglens.explain.ReferenceSource.Reference;
import com.pglens.explain.llm.LlmSettings;
import com.pglens.explain.llm.OpenAiCompatibleClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs the frozen eval cases through the real explainer and writes every attempt for grading
 * ({@code docs/llm-eval.md}). {@code ./gradlew :explain:llmEval -Psplit=dev|heldout|all
 * [-Pmodel=qwen3.5:4b] [-Pdocs=true]} — needs a running model; never part of the build.
 *
 * <p>Condition B ({@code docs=true}) embeds the server's PostgreSQL-docs corpus with the real
 * embedding model and retrieves by exact cosine search in memory — the answer an exact pgvector
 * scan gives; HNSW's agreement with it is measured separately (M7).
 */
public final class EvalRunner {

  private EvalRunner() {}

  /** One corpus passage with its embedding. */
  private record Passage(String id, Reference ref, float[] vector) {}

  public static void main(String[] args) throws Exception {
    String split = args[0];
    LlmSettings settings = LlmSettings.defaults();
    if (!args[1].isBlank()) {
      settings = settings.withModel(args[1]);
    }
    if (!args[2].isBlank()) {
      settings = settings.withBaseUrl(args[2]);
    }
    Path out = Path.of(args[3]);
    boolean docs = Boolean.parseBoolean(args[4]);
    Path corpus = Path.of(args[5]);
    Files.createDirectories(out);

    OpenAiCompatibleClient client = new OpenAiCompatibleClient(settings);
    ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    List<Passage> passages = docs ? embedCorpus(client, corpus, json) : List.of();
    List<List<String>> retrieved = new ArrayList<>();
    ReferenceSource refs =
        !docs
            ? ReferenceSource.NONE
            : facts -> {
              List<Passage> top = nearest(client, passages, ReferenceSource.signals(facts), 3);
              retrieved.add(top.stream().map(Passage::id).toList());
              return top.stream().map(Passage::ref).toList();
            };
    Explainer explainer = new Explainer(client, refs, true);

    ArrayNode results = json.createArrayNode();
    for (EvalCases.Case c : EvalCases.all()) {
      if (!split.equals("all") && !c.split().equals(split)) {
        continue;
      }
      retrieved.clear();
      Explainer.Trace t = explainer.trace(c.target());
      ObjectNode row = results.addObject();
      row.put("id", c.id());
      row.put("split", c.split());
      row.put("condition", docs ? "B-cards+docs" : "A-cards");
      row.put("signals", ReferenceSource.signals(c.facts()));
      row.set("retrieved", json.valueToTree(retrieved.isEmpty() ? List.of() : retrieved.get(0)));
      row.set("explanation", json.valueToTree(t.explanation()));
      row.set("attempts", json.valueToTree(t.attempts()));
      System.out.printf(
          "%s %s attempts=%d %s%n",
          c.id(),
          t.explanation().source(),
          t.attempts().size(),
          t.attempts().stream().map(a -> a.elapsedMs() + "ms " + a.violations()).toList());
    }
    Path file =
        out.resolve(
            settings.model().replace(':', '_') + "-" + split + (docs ? "-docs" : "") + ".json");
    json.writeValue(file.toFile(), results);
    System.out.println("wrote " + file.toAbsolutePath());
  }

  private static List<Passage> embedCorpus(
      OpenAiCompatibleClient client, Path corpus, ObjectMapper json) throws Exception {
    List<JsonNode> rows = new ArrayList<>();
    for (String line : Files.readAllLines(corpus)) {
      if (!line.isBlank()) {
        rows.add(json.readTree(line));
      }
    }
    List<Passage> out = new ArrayList<>();
    for (int i = 0; i < rows.size(); i += 16) {
      List<JsonNode> batch = rows.subList(i, Math.min(i + 16, rows.size()));
      List<float[]> vectors =
          client.embed(
              batch.stream()
                  .map(
                      r ->
                          "search_document: "
                              + r.get("title").asText()
                              + "\n"
                              + r.get("text").asText())
                  .toList());
      for (int j = 0; j < batch.size(); j++) {
        JsonNode r = batch.get(j);
        out.add(
            new Passage(
                r.get("id").asText(),
                new Reference(
                    r.get("title").asText(), r.get("text").asText(), r.get("url").asText()),
                vectors.get(j)));
      }
    }
    System.out.println("embedded " + out.size() + " passages");
    return out;
  }

  private static List<Passage> nearest(
      OpenAiCompatibleClient client, List<Passage> passages, String query, int k) {
    try {
      float[] q = client.embed(List.of("search_query: " + query)).get(0);
      return passages.stream()
          .sorted(Comparator.comparingDouble((Passage p) -> -cosine(q, p.vector())))
          .limit(k)
          .toList();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static double cosine(float[] a, float[] b) {
    double dot = 0;
    double na = 0;
    double nb = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      na += a[i] * a[i];
      nb += b[i] * b[i];
    }
    return dot / Math.sqrt(na * nb);
  }
}
