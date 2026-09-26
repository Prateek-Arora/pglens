package com.pglens.server.explain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pglens.engine.model.QueryReport;
import com.pglens.engine.model.Recommendation;
import com.pglens.explain.ExplanationFacts;
import com.pglens.explain.ExplanationTarget;
import com.pglens.explain.FactsBuilder;
import com.pglens.explain.ReferenceSource;
import com.pglens.explain.llm.LlmSettings;
import com.pglens.explain.llm.OpenAiCompatibleClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * M7 of the Phase 3 eval ({@code docs/llm-eval.md}): real embeddings in real pgvector, HNSW vs an
 * exact scan, scored against passages labelled relevant before retrieval ran. Needs a running
 * embedding model, so it only runs as {@code ./gradlew :server:retrievalEval}; writes {@code
 * build/llm-eval/retrieval.json}.
 */
@Tag("llm")
class RetrievalEvalTest {

  @Test
  void hnswVersusExactRecallAt3() throws Exception {
    Path root = Path.of(System.getProperty("pglens.repoRoot"));
    ObjectMapper json =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    JsonNode labels =
        json.readTree(
                root.resolve("explain/src/test/resources/eval/retrieval-labels.json").toFile())
            .get("queries");

    // One retrieval query per rule set (the signals don't depend on the schema).
    Map<String, String> queries = new LinkedHashMap<>();
    try (Stream<Path> files = Files.list(root.resolve("explain/src/test/resources/eval/cases"))) {
      for (Path f : files.sorted().toList()) {
        JsonNode in = json.readTree(f.toFile()).get("input");
        Recommendation rec = json.treeToValue(in.get("recommendation"), Recommendation.class);
        ExplanationFacts facts =
            FactsBuilder.build(
                new ExplanationTarget(
                    json.treeToValue(in.get("query"), QueryReport.class), rec, 0));
        queries.putIfAbsent(
            String.join("+", rec.candidate().sourceRuleIds()), ReferenceSource.signals(facts));
      }
    }

    try (PostgreSQLContainer pg =
        new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg16")
                .asCompatibleSubstituteFor("postgres"))) {
      pg.start();
      DriverManagerDataSource ds =
          new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
      Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
      JdbcTemplate jdbc = new JdbcTemplate(ds);
      KnowledgeStore store =
          new KnowledgeStore(
              jdbc,
              new TransactionTemplate(new DataSourceTransactionManager(ds)),
              new OpenAiCompatibleClient(
                  LlmSettings.defaults()
                      .withBaseUrl(
                          System.getenv()
                              .getOrDefault("PGLENS_LLM_URL", LlmSettings.DEFAULT_BASE_URL))));
      store.ensureLoaded();

      ObjectNode out = json.createObjectNode();
      double exactSum = 0;
      double hnswSum = 0;
      int agree = 0;
      for (Map.Entry<String, String> q : queries.entrySet()) {
        List<String> exact = store.searchIds(q.getValue(), 3, true);
        List<String> hnsw = store.searchIds(q.getValue(), 3, false);
        List<String> relevant = List.of(json.treeToValue(labels.get(q.getKey()), String[].class));
        double re = recall(exact, relevant);
        double rh = recall(hnsw, relevant);
        exactSum += re;
        hnswSum += rh;
        agree += exact.equals(hnsw) ? 1 : 0;
        ObjectNode row = out.putObject(q.getKey());
        row.put("signals", q.getValue());
        row.set("exact", json.valueToTree(exact));
        row.set("hnsw", json.valueToTree(hnsw));
        row.put("recallExact", re);
        row.put("recallHnsw", rh);
      }
      out.put("meanRecallExact", exactSum / queries.size());
      out.put("meanRecallHnsw", hnswSum / queries.size());
      out.put("identicalTop3", agree + " of " + queries.size());
      Path file = root.resolve("server/build/llm-eval/retrieval.json");
      Files.createDirectories(file.getParent());
      json.enable(SerializationFeature.INDENT_OUTPUT).writeValue(file.toFile(), out);
      System.out.println(out.toPrettyString());
      assertThat(queries).hasSize(5);
    }
  }

  private static double recall(List<String> got, List<String> relevant) {
    long hits = got.stream().filter(relevant::contains).count();
    return (double) hits / Math.min(got.size(), relevant.size());
  }
}
