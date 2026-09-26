package com.pglens.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.isA;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pglens.agent.config.GrpcClientConfig;
import com.pglens.agent.config.PglensAgentProperties;
import com.pglens.agent.grpc.IngestClient;
import com.pglens.agent.grpc.ValidationClient;
import com.pglens.agent.sample.SampleCollector;
import com.pglens.agent.sample.ValidationRunner;
import com.pglens.engine.db.CatalogReader;
import com.pglens.engine.db.DataSources;
import com.pglens.engine.db.HypoPGValidator;
import com.pglens.engine.db.PlanCapturer;
import com.pglens.engine.db.StatsReader;
import com.pglens.engine.model.ConnectionTarget;
import com.pglens.explain.Explanation;
import com.pglens.explain.ExplanationTarget;
import com.pglens.explain.FactsBuilder;
import com.pglens.explain.FactsHash;
import com.pglens.server.analysis.AnalysisService;
import com.pglens.server.auth.Tokens;
import com.pglens.server.explain.ExplanationInputs;
import com.pglens.server.explain.ExplanationRepository;
import com.pglens.server.grpc.GrpcServerLifecycle;
import com.pglens.server.grpc.GrpcTestTls;
import com.pglens.server.persistence.MonitoredDbRepository;
import io.grpc.ManagedChannel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The read API (ADR-0044, Phase 4 Step 4) over real data: a real monitored Postgres, the real agent
 * components sampling it over TLS gRPC, the analysis pass and an edge HypoPG validation — then
 * every endpoint read back over HTTP. Checks the contract promises: {@code queryid} is a JSON
 * string, findings point at real plan nodes, numbers carry their labels, and the explanation is the
 * template unless an LLM answer for the very same facts is cached.
 */
@Tag("it")
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
    properties = {
      "pglens.grpc.port=0",
      "pglens.analysis.initial-delay-ms=3600000",
      "pglens.analysis.interval-ms=3600000",
      "pglens.auth.admin-password=" + ReadApiIntegrationTest.ADMIN_PASSWORD
    })
class ReadApiIntegrationTest {

  static final String ADMIN_PASSWORD = "admin password 12";
  private static final String TOKEN = "read-api-agent-token";
  private static final String DB = "demo";
  private static final String API = "/api/v1/databases/" + DB;

  @Container
  static final PostgreSQLContainer METADATA =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:0.8.6-pg16")
              .asCompatibleSubstituteFor("postgres"));

  @Container
  static final PostgreSQLContainer MONITORED =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      System.getProperty("pglens.monitoredImage", "pglens/monitored-db:0.0.0"))
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("pglens_demo")
          .withCommand(
              "postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_stat_statements");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", METADATA::getJdbcUrl);
    registry.add("spring.datasource.username", METADATA::getUsername);
    registry.add("spring.datasource.password", METADATA::getPassword);
    GrpcTestTls.register(registry);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate metadata;
  @Autowired GrpcServerLifecycle grpcServer;
  @Autowired MonitoredDbRepository monitoredDbs;
  @Autowired AnalysisService analysis;
  @Autowired ExplanationInputs explanationInputs;
  @Autowired ExplanationRepository explanations;

  private static boolean pipelineRan;
  private static long ordersQueryId;
  private static SingleConnectionDataSource agentDs;
  private String admin;

  /** Runs the real pipeline once; every test only reads. */
  @BeforeEach
  void pipeline() throws Exception {
    admin = login();
    if (pipelineRan) {
      return;
    }
    ConnectionTarget target =
        new ConnectionTarget(
            MONITORED.getJdbcUrl(),
            MONITORED.getUsername(),
            MONITORED.getPassword(),
            "pglens_demo");
    JdbcTemplate su = new JdbcTemplate(DataSources.forScan(target));
    su.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
    su.execute("CREATE EXTENSION IF NOT EXISTS hypopg");
    su.execute(
        "CREATE TABLE orders (id serial PRIMARY KEY, customer_id int NOT NULL, "
            + "status text NOT NULL, created_at timestamptz NOT NULL)");
    su.execute(
        "INSERT INTO orders (customer_id, status, created_at) SELECT (random() * 999 + 1)::int,"
            + " 'completed', now() - (g || ' minutes')::interval FROM generate_series(1, 20000) g");
    // A jsonb containment filter: PgLens suggests GIN, which HypoPG can't simulate (charter #6).
    su.execute("CREATE TABLE events (id serial PRIMARY KEY, payload jsonb NOT NULL)");
    su.execute(
        "INSERT INTO events (payload) SELECT jsonb_build_object('k', g % 500) "
            + "FROM generate_series(1, 20000) g");
    su.execute("ANALYZE orders; ANALYZE events");
    su.execute("SELECT pg_stat_statements_reset()");

    monitoredDbs.register(DB, "monitored-db", Tokens.sha256Hex(TOKEN));
    agentDs = DataSources.forScan(target);
    JdbcTemplate agentJdbc = new JdbcTemplate(agentDs);
    PglensAgentProperties props = new PglensAgentProperties();
    props.setDbName(DB);
    props.setToken(TOKEN);
    props.getSample().setTopN(200);
    props.getSample().setMinCalls(1);
    props.getValidation().setMaxLease(10);
    props.getServer().setCaCert(GrpcTestTls.CA.getAbsolutePath());
    props.getServer().setAuthority(GrpcTestTls.AUTHORITY);
    ManagedChannel channel =
        GrpcClientConfig.channel("localhost", grpcServer.getPort(), props.getServer());
    try {
      SampleCollector collector =
          new SampleCollector(
              props,
              agentJdbc,
              agentDs,
              new StatsReader(agentJdbc),
              new CatalogReader(agentJdbc),
              new PlanCapturer(agentJdbc),
              new IngestClient(channel, TOKEN));
      warm(su, 10);
      collector.sample(); // anchor + plans + catalog
      warm(su, 10);
      collector.sample(); // a delta row per query
      analysis.run();
      new ValidationRunner(
              props,
              agentJdbc,
              new HypoPGValidator(agentJdbc),
              new ValidationClient(channel, TOKEN))
          .validatePending();
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
    ordersQueryId =
        metadata.queryForObject(
            "SELECT queryid FROM query_texts WHERE normalized_text LIKE '%FROM orders%customer_id%'",
            Long.class);
    pipelineRan = true;
  }

  @AfterAll
  static void closeAgentConnection() {
    if (agentDs != null) {
      agentDs.destroy();
    }
  }

  @Test
  void theLeaderboardRanksWindowedTotalsWithQueryidsAsStrings() throws Exception {
    String q = Long.toString(ordersQueryId);
    mvc.perform(authed(get(API + "/queries?window=24h&sort=total"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.window").value("24h"))
        .andExpect(jsonPath("$.from").value(org.hamcrest.Matchers.endsWith(":00:00Z")))
        .andExpect(jsonPath("$.total").value(greaterThan(1)))
        .andExpect(jsonPath("$.items[0].queryid").value(isA(String.class)))
        .andExpect(
            jsonPath("$.items[?(@.queryid == '" + q + "')].recommendation")
                .value("PLANNER_VALIDATED"))
        .andExpect(jsonPath("$.items[?(@.queryid == '" + q + "')].calls").value(10))
        .andExpect(jsonPath("$.items[?(@.queryid == '" + q + "')].planCaptured").value(true));

    String events =
        Long.toString(
            metadata.queryForObject(
                "SELECT queryid FROM query_texts WHERE normalized_text LIKE '%FROM events%'",
                Long.class));
    mvc.perform(authed(get(API + "/queries"), admin))
        .andExpect(
            jsonPath("$.items[?(@.queryid == '" + events + "')].recommendation")
                .value("NOT_PLANNER_VALIDATED"));

    String all = body(authed(get(API + "/queries?sort=calls"), admin));
    String second = body(authed(get(API + "/queries?sort=calls&limit=1&offset=1"), admin));
    List<String> ids = JsonPath.read(all, "$.items[*].queryid");
    assertThat(JsonPath.<List<String>>read(second, "$.items[*].queryid"))
        .containsExactly(ids.get(1));
    assertThat(JsonPath.<Integer>read(second, "$.total"))
        .isEqualTo(JsonPath.<Integer>read(all, "$.total"));
    // Past the last page: no rows, but the total is still the real count.
    mvc.perform(authed(get(API + "/queries?offset=1000"), admin))
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.total").value(ids.size()));

    for (String bad : List.of("window=1y", "sort=rows", "limit=201", "offset=-1")) {
      mvc.perform(authed(get(API + "/queries?" + bad), admin))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.detail").isNotEmpty());
    }
  }

  @Test
  void theDetailShowsTheEstimatedPlanWithFindingsPointingAtItsNodes() throws Exception {
    String body =
        body(
            authed(get(API + "/queries/" + ordersQueryId), admin),
            jsonPath("$.queryid").value(Long.toString(ordersQueryId)),
            jsonPath("$.sql").value(containsString("customer_id")),
            jsonPath("$.inWindow.calls").value(10), // the first sample only anchors
            jsonPath("$.sinceStatsReset.calls").value(20),
            jsonPath("$.inWindow.measuredTotalMs").value(greaterThan(0.0), Double.class),
            jsonPath("$.plan.captureMode").value("generic_plan"),
            jsonPath("$.plan.label").value(containsString("estimates")),
            jsonPath("$.plan.root.id").value(0),
            jsonPath("$.recommendations[0].status").value("PLANNER_VALIDATED"),
            jsonPath("$.recommendations[0].ddl").value(containsString("customer_id")),
            jsonPath("$.confirm.caveat").value(containsString("Planner-validated ≠ safe")),
            jsonPath("$.confirm.command").value(containsString("pglens confirm --report")));

    Map<String, Object> finding = JsonPath.read(body, "$.findings[0]");
    assertThat(finding.get("ruleId")).isEqualTo("R1");
    List<Map<String, Object>> node =
        JsonPath.read(body, "$.plan..[?(@.id == " + finding.get("planNode") + ")]");
    assertThat(node)
        .singleElement()
        .satisfies(
            n -> {
              assertThat(n.get("nodeType")).isEqualTo("Seq Scan");
              assertThat(n.get("relation")).isEqualTo("orders");
            });
    double before = JsonPath.read(body, "$.recommendations[0].plannerCostBefore");
    double after = JsonPath.read(body, "$.recommendations[0].plannerCostAfter");
    assertThat(after).isLessThan(before);

    mvc.perform(authed(get(API + "/queries/not-a-number"), admin))
        .andExpect(status().isBadRequest());
    mvc.perform(authed(get(API + "/queries/123"), admin)).andExpect(status().isNotFound());
    mvc.perform(authed(get("/api/v1/databases/nope/queries/" + ordersQueryId), admin))
        .andExpect(status().isNotFound());
  }

  @Test
  void theTrendHasOnePointPerPersistedInterval() throws Exception {
    mvc.perform(authed(get(API + "/queries/" + ordersQueryId + "/trend"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.queryid").value(Long.toString(ordersQueryId)))
        .andExpect(jsonPath("$.resolution").value("RAW")) // auto: raw up to 48 h
        .andExpect(jsonPath("$.points.length()").value(1))
        .andExpect(jsonPath("$.points[0].calls").value(10))
        .andExpect(jsonPath("$.points[0].measuredMeanMs").value(greaterThan(0.0), Double.class));

    String hourly =
        body(
            authed(get(API + "/queries/" + ordersQueryId + "/trend?resolution=hour"), admin),
            jsonPath("$.resolution").value("HOUR"),
            jsonPath("$.points[0].calls").value(10));
    assertThat(JsonPath.<String>read(hourly, "$.points[0].capturedAt")).endsWith(":00:00Z");
    mvc.perform(authed(get(API + "/queries/" + ordersQueryId + "/trend?resolution=day"), admin))
        .andExpect(status().isBadRequest());

    Instant now = Instant.now();
    mvc.perform(
            authed(
                get(API + "/queries/" + ordersQueryId + "/trend?from=" + now + "&to=" + now),
                admin))
        .andExpect(status().isBadRequest());
    mvc.perform(
            authed(
                get(
                    API
                        + "/queries/"
                        + ordersQueryId
                        + "/trend?from=2026-01-01T00:00:00Z&to=2026-03-01T00:00:00Z"),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("31 days")));
  }

  @Test
  void explanationsAreTheTemplateUnlessAnLlmAnswerForTheSameFactsIsCached() throws Exception {
    String url = API + "/queries/" + ordersQueryId + "/explanation";
    mvc.perform(authed(get(url), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.explanations[0].source").value("TEMPLATE"))
        .andExpect(jsonPath("$.explanations[0].summary").value(containsString("planner")))
        .andExpect(jsonPath("$.explanations[0].model").doesNotExist());

    long dbId = metadata.queryForObject("SELECT id FROM monitored_dbs", Long.class);
    ExplanationTarget target = explanationInputs.targets(dbId, 5).get(0); // the orders index
    String ddl = target.recommendation().candidate().ddl();
    Explanation llm =
        new Explanation(
            ddl,
            Explanation.Source.LLM,
            "LLM summary.",
            "LLM why.",
            "LLM what.",
            "test-model",
            null,
            List.of(),
            List.of());
    // An answer for other facts (stale) is never shown ...
    explanations.put(
        new ExplanationRepository.Key(dbId, ordersQueryId, ddl, "stale-facts", "test-model", "v"),
        llm,
        null);
    mvc.perform(authed(get(url), admin))
        .andExpect(jsonPath("$.explanations[0].source").value("TEMPLATE"));
    // ... an answer for exactly these facts is, with its label.
    explanations.put(
        new ExplanationRepository.Key(
            dbId, ordersQueryId, ddl, FactsHash.of(FactsBuilder.build(target)), "test-model", "v"),
        llm,
        null);
    mvc.perform(authed(get(url), admin))
        .andExpect(jsonPath("$.explanations[0].source").value("LLM"))
        .andExpect(jsonPath("$.explanations[0].summary").value("LLM summary."))
        .andExpect(jsonPath("$.explanations[0].model").value("test-model"))
        .andExpect(jsonPath("$.explanations[0].promptVersion").value("v"))
        .andExpect(jsonPath("$.explanations[0].generatedAt").isNotEmpty());
    metadata.update("DELETE FROM explanations");
  }

  @Test
  void recommendationsCarryTheCaveatAndSurfaceWhatHypoPgCannotCheck() throws Exception {
    mvc.perform(authed(get(API + "/recommendations"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.confirm.command").value(containsString("pglens confirm")))
        .andExpect(jsonPath("$.recommended[0].ddl").value(containsString("customer_id")))
        .andExpect(jsonPath("$.recommended[0].actionable").value(true))
        .andExpect(
            jsonPath("$.recommended[0].estimatedMsSaved").value(greaterThan(0.0), Double.class))
        .andExpect(
            jsonPath("$.recommended[0].queries[0].queryid").value(Long.toString(ordersQueryId)))
        .andExpect(jsonPath("$.notPlannerValidated[0].accessMethod").value("GIN"))
        .andExpect(jsonPath("$.notPlannerValidated[0].queryids[0]").value(isA(String.class)));

    mvc.perform(authed(get("/api/v1/recommendations?limit=5"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recommended[0].database").value(DB))
        .andExpect(jsonPath("$.confirm.caveat").isNotEmpty());
  }

  @Test
  void hygieneTopMoversAndNewSlowQueriesRead() throws Exception {
    mvc.perform(authed(get(API + "/hygiene"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.database").value(DB))
        .andExpect(jsonPath("$.findings").isArray());
    mvc.perform(authed(get(API + "/top-movers?window=7d"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].queryid").value(hasItem(Long.toString(ordersQueryId))))
        .andExpect(jsonPath("$.items[0].pctChange").doesNotExist()); // no prior load: null
    mvc.perform(authed(get(API + "/new-slow?minTotalMs=0"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].queryid").value(hasItem(Long.toString(ordersQueryId))));
  }

  @Test
  void anApiTokenReadsEverythingAndTheOpenApiSpecIsPublic() throws Exception {
    String apiToken =
        JsonPath.read(
            body(
                authed(post("/api/v1/api-tokens"), admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"read-api-it\"}")),
            "$.token");
    for (String path :
        List.of(
            API + "/queries",
            API + "/queries/" + ordersQueryId,
            API + "/recommendations",
            "/api/v1/recommendations")) {
      mvc.perform(authed(get(path), apiToken)).andExpect(status().isOk());
    }
    mvc.perform(get(API + "/queries")).andExpect(status().isUnauthorized());

    String spec = body(get("/api/v1/openapi.json"));
    assertThat(JsonPath.<Map<String, Object>>read(spec, "$.paths"))
        .containsKeys(
            "/api/v1/databases/{db}/queries",
            "/api/v1/databases/{db}/queries/{queryid}",
            "/api/v1/databases/{db}/queries/{queryid}/explanation",
            "/api/v1/recommendations");
    assertThat(
            JsonPath.<List<String>>read(
                spec,
                "$.paths['/api/v1/databases/{db}/queries/{queryid}'].get.parameters"
                    + "[?(@.name == 'queryid')].schema.type"))
        .containsExactly("string");
    assertThat(
            JsonPath.<String>read(
                spec, "$.components.schemas.LeaderboardEntry.properties" + ".queryid.type"))
        .isEqualTo("string");
    assertThat(JsonPath.<Object>read(spec, "$.components.securitySchemes.bearer")).isNotNull();
  }

  private static void warm(JdbcTemplate su, int n) {
    for (int i = 0; i < n; i++) {
      su.queryForList("SELECT * FROM orders WHERE customer_id = 42");
      su.queryForList("SELECT * FROM events WHERE payload @> '{\"k\": 7}'");
    }
  }

  private String login() throws Exception {
    return JsonPath.read(
        body(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}")),
        "$.token");
  }

  private String body(
      MockHttpServletRequestBuilder request,
      org.springframework.test.web.servlet.ResultMatcher... expectations)
      throws Exception {
    var result = mvc.perform(request);
    for (var e : expectations) {
      result.andExpect(e);
    }
    return result.andReturn().getResponse().getContentAsString();
  }

  private static MockHttpServletRequestBuilder authed(
      MockHttpServletRequestBuilder request, String token) {
    return request.header("Authorization", "Bearer " + token);
  }
}
