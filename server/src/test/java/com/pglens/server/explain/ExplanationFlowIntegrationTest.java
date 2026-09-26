package com.pglens.server.explain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pglens.explain.Explanation;
import com.pglens.explain.Explanation.Source;
import com.pglens.explain.llm.LlmSettings;
import com.pglens.explain.llm.OpenAiCompatibleClient;
import com.pglens.explain.llm.StubLlmServer;
import com.pglens.proto.v1.CatalogSnapshot;
import com.pglens.proto.v1.IndexStat;
import com.pglens.proto.v1.TableStat;
import com.pglens.server.auth.Tokens;
import com.pglens.server.grpc.GrpcTestTls;
import com.pglens.server.persistence.CatalogRepository;
import com.pglens.server.persistence.MonitoredDbRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The server's explanation pass (ADR-0043) over a real pgvector metadata DB and a scripted LLM: the
 * docs corpus is embedded once, an index is explained once per exact input, an outage is cached as
 * a template with a retry time, and a model change regenerates. No model runs in CI.
 */
@Tag("it")
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "pglens.grpc.port=0",
      "pglens.analysis.initial-delay-ms=3600000",
      "pglens.analysis.interval-ms=3600000",
      "pglens.explain.enabled=true",
      "pglens.explain.initial-delay-ms=3600000",
      "pglens.explain.interval-ms=3600000"
    })
class ExplanationFlowIntegrationTest {

  @Container
  static final PostgreSQLContainer METADATA =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:0.8.6-pg16")
              .asCompatibleSubstituteFor("postgres"));

  static final StubLlmServer LLM = start();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", METADATA::getJdbcUrl);
    registry.add("spring.datasource.username", METADATA::getUsername);
    registry.add("spring.datasource.password", METADATA::getPassword);
    GrpcTestTls.register(registry);
    registry.add("pglens.llm.base-url", LLM::baseUrl);
  }

  private static final long QUERYID = 5150L;
  private static final String DDL = "CREATE INDEX idx_customers_email ON customers (email);";
  private static final String PLAN =
      """
      [{"Plan": {"Node Type": "Seq Scan", "Relation Name": "customers", "Alias": "customers",
        "Startup Cost": 0.00, "Total Cost": 473.00, "Plan Rows": 1, "Plan Width": 39,
        "Output": ["id", "full_name", "email"], "Filter": "(customers.email = $1)"}}]
      """;
  private static final String GOOD_ANSWER =
      json(
          "The planner estimates an index on customers (email) cuts this query's cost by 90.0%.",
          "Postgres reads every row of customers to find the one matching email.",
          "With the index, Postgres goes straight to the matching row.");

  @Autowired ExplanationService service;
  @Autowired ExplanationInputs inputs;
  @Autowired ExplanationRepository cache;
  @Autowired MonitoredDbRepository monitoredDbs;
  @Autowired CatalogRepository catalogs;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired LlmSettings settings;

  private long dbId;

  @BeforeEach
  void seed() {
    jdbc.execute(
        "TRUNCATE monitored_dbs, query_texts, query_cumulative, table_catalog, index_catalog, "
            + "recommendations, explanations RESTART IDENTITY CASCADE");
    dbId = monitoredDbs.register("demo", "monitored-db", Tokens.sha256Hex("t"));
    catalogs.replaceCatalog(
        dbId,
        CatalogSnapshot.newBuilder()
            .addTables(TableStat.newBuilder().setTableName("customers").setEstRows(1_000_000))
            .addIndexes(
                IndexStat.newBuilder()
                    .setIndexName("customers_pkey")
                    .setTableName("customers")
                    .setIsUnique(true)
                    .setIsPrimary(true)
                    .setMethod("btree")
                    .addColumns("id"))
            .build());
    jdbc.update(
        "INSERT INTO query_texts (db_id, queryid, text_hash, normalized_text, plan_json, "
            + "plan_captured) VALUES (?, ?, 'h', "
            + "'select id, full_name, email from customers where email = $1', ?, true)",
        dbId,
        QUERYID,
        PLAN);
    jdbc.update(
        "INSERT INTO query_cumulative (db_id, queryid, calls, total_exec_time_ms, rows, "
            + "shared_blks_hit, shared_blks_read, captured_at) VALUES (?, ?, 40, 800.0, 40, 0, 0, now())",
        dbId,
        QUERYID);
    jdbc.update(
        "INSERT INTO recommendations (db_id, queryid, ddl, access_method, status, before_cost, "
            + "after_cost, relative_drop, used, reason, estimated_ms_saved, score_basis) "
            + "VALUES (?, ?, ?, 'BTREE', 'PLANNER_VALIDATED', 473.0, 47.3, 0.9, true, "
            + "'Planner-validated (HypoPG estimate): total cost 473 → 47 (−90.0%).', 720.0, "
            + "'GENERIC_PLAN')",
        dbId, QUERYID, DDL);
  }

  @AfterAll
  static void stop() {
    LLM.close();
  }

  @Test
  void theServerRebuildsTheSameFactsTheCliWouldSee() {
    var targets = inputs.targets(dbId, 5);

    assertThat(targets).hasSize(1);
    var t = targets.get(0);
    assertThat(t.recommendation().candidate().ddl()).isEqualTo(DDL);
    assertThat(t.recommendation().candidate().sourceRuleIds()).containsExactly("R1");
    assertThat(t.query().findings()).extracting(f -> f.ruleId()).containsExactly("R1");
    assertThat(t.query().calls()).isEqualTo(40);
    assertThat(t.query().meanExecMs()).isEqualTo(20.0);
  }

  @Test
  void anIndexIsExplainedOnceAndTheDocsAreEmbeddedOnce() {
    long chatsBefore = LLM.count("/chat/completions");
    LLM.replyContent(GOOD_ANSWER);

    assertThat(service.run()).isEqualTo(1);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks", Integer.class))
        .isEqualTo(KnowledgeStore.corpus().size());
    Explanation e = cache.latest(dbId, DDL).orElseThrow();
    assertThat(e.source()).isEqualTo(Source.LLM);
    assertThat(e.model()).isEqualTo("qwen3.5:4b");
    assertThat(e.summary()).contains("90.0%");
    // Retrieved passages add further-reading links (docs-in-prompt is off by default).
    assertThat(e.docs()).anyMatch(u -> u.startsWith("https://www.postgresql.org/docs/16/"));
    assertThat(LLM.count("/chat/completions") - chatsBefore).isEqualTo(1);

    // Same facts, model and prompt: nothing to do, and no model call.
    long embedsBefore = LLM.count("/embeddings");
    assertThat(service.run()).isZero();
    assertThat(LLM.count("/chat/completions") - chatsBefore).isEqualTo(1);
    // Only the retrieval query embeds on a later pass — the corpus isn't re-embedded.
    assertThat(LLM.count("/embeddings") - embedsBefore).isZero();
  }

  @Test
  void newFactsRegenerate() {
    LLM.replyContent(GOOD_ANSWER).replyContent(GOOD_ANSWER);
    service.run();

    jdbc.update("UPDATE query_cumulative SET calls = 80, total_exec_time_ms = 1600.0");

    assertThat(service.run()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM explanations", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void aModelChangeRegenerates() {
    LLM.replyContent(GOOD_ANSWER).replyContent(GOOD_ANSWER);
    service.run();

    ExplanationService otherModel =
        new ExplanationService(
            monitoredDbs,
            inputs,
            cache,
            new OpenAiCompatibleClient(settings.withModel("qwen3.5:2b")),
            jdbc,
            tx,
            5,
            5,
            3_600_000,
            false);

    assertThat(otherModel.run()).isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT model FROM explanations ORDER BY model", String.class))
        .containsExactly("qwen3.5:2b", "qwen3.5:4b");
  }

  @Test
  void anOutageIsCachedAsATemplateAndRetriedLater() {
    LLM.reply(500, "{\"error\":\"model requires more system memory\"}");

    assertThat(service.run()).isEqualTo(1);

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM explanations");
    assertThat(row.get("source")).isEqualTo("TEMPLATE");
    assertThat((String) row.get("fallback_reason")).contains("HTTP 500");
    assertThat(row.get("retry_after")).isNotNull();
    assertThat(service.run()).isZero(); // still inside the retry window

    jdbc.update("UPDATE explanations SET retry_after = now() - interval '1 second'");
    LLM.replyContent(GOOD_ANSWER);

    assertThat(service.run()).isEqualTo(1);
    assertThat(cache.latest(dbId, DDL).orElseThrow().source()).isEqualTo(Source.LLM);
    assertThat(jdbc.queryForObject("SELECT retry_after FROM explanations", Object.class)).isNull();
  }

  @Test
  void aRejectedAnswerIsCachedForGood() {
    String bad = json("It gets 10× faster.", "Postgres reads customers.", "CREATE INDEX x;");
    LLM.replyContent(bad).replyContent(bad);

    service.run();

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM explanations");
    assertThat(row.get("source")).isEqualTo("TEMPLATE");
    assertThat(row.get("retry_after")).isNull(); // same input, same answer at temperature 0
    assertThat(cache.latest(dbId, DDL).orElseThrow().violations())
        .anyMatch(v -> v.startsWith("sql:"));
  }

  @Test
  void hnswAndExactSearchAgreeOnASmallCorpus() throws Exception {
    KnowledgeStore store = new KnowledgeStore(jdbc, tx, new OpenAiCompatibleClient(settings));
    store.ensureLoaded();

    var hnsw = store.search("Selective sequential scan. B-tree index", 3, false);
    var exact = store.search("Selective sequential scan. B-tree index", 3, true);

    assertThat(hnsw).hasSize(3);
    assertThat(hnsw)
        .extracting(r -> r.url())
        .containsExactlyElementsOf(exact.stream().map(r -> r.url()).toList());
  }

  private static StubLlmServer start() {
    try {
      return new StubLlmServer().autoEmbeddings(768);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String json(String summary, String why, String change) {
    try {
      return new ObjectMapper()
          .writeValueAsString(
              Map.of("summary", summary, "whyItIsSlow", why, "whatTheIndexChanges", change));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
