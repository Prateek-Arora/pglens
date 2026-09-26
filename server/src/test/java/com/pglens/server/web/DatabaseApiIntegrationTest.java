package com.pglens.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pglens.agent.grpc.IngestClient;
import com.pglens.proto.v1.QueryStatSample;
import com.pglens.proto.v1.SampleBatch;
import com.pglens.server.grpc.GrpcServerLifecycle;
import com.pglens.server.grpc.GrpcTestTls;
import io.grpc.ManagedChannel;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Registration through the API (B10, ADR-0044): the minted token really authenticates an agent over
 * TLS gRPC, liveness follows ingest, rotation cuts the old token, delete needs confirmation.
 */
@Tag("it")
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
    properties = {
      "pglens.grpc.port=0",
      "pglens.analysis.initial-delay-ms=3600000",
      "pglens.auth.admin-password=" + DatabaseApiIntegrationTest.ADMIN_PASSWORD,
      "pglens.api.agent-stale-after=5m"
    })
class DatabaseApiIntegrationTest {

  static final String ADMIN_PASSWORD = "admin password 12";

  @Container
  static final PostgreSQLContainer METADATA =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:0.8.6-pg16")
              .asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", METADATA::getJdbcUrl);
    registry.add("spring.datasource.username", METADATA::getUsername);
    registry.add("spring.datasource.password", METADATA::getPassword);
    GrpcTestTls.register(registry);
  }

  @TestConfiguration
  static class Clocks {
    // Starts at the real time: ingest stamps batches with the real clock.
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(Instant.now());
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired MutableClock clock;
  @Autowired GrpcServerLifecycle grpcServer;

  private String admin;

  @BeforeEach
  void setUp() throws Exception {
    jdbc.update("DELETE FROM monitored_dbs");
    jdbc.update("DELETE FROM users WHERE username <> 'admin'");
    admin = login("admin", ADMIN_PASSWORD);
  }

  @Test
  void anAdminRegistersADatabaseAndItsTokenAuthenticatesTheAgent() throws Exception {
    String body =
        register("shop")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.database.name").value("shop"))
            .andExpect(jsonPath("$.database.agent").value("NEVER_CONNECTED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = JsonPath.read(body, "$.agentToken");
    assertThat(token).startsWith("pglens_g_");
    assertThat(jdbc.queryForObject("SELECT agent_token_hash FROM monitored_dbs", String.class))
        .hasSize(64)
        .isNotEqualTo(token);

    assertThat(ingest(token).getAcceptedSamples()).isEqualTo(1);

    mvc.perform(authed(get("/api/v1/databases/shop"), admin))
        .andExpect(jsonPath("$.agent").value("CONNECTED"))
        .andExpect(jsonPath("$.lastIngestAt").isNotEmpty());
    clock.advance(Duration.ofMinutes(6));
    mvc.perform(authed(get("/api/v1/databases"), admin))
        .andExpect(jsonPath("$[0].agent").value("STALE"));
  }

  /** A database registered before V9 has history but no last_ingest_at: its last ingest shows. */
  @Test
  void historyFromBeforeTheUpgradeCountsAsTheLastIngest() throws Exception {
    register("shop").andExpect(status().isCreated());
    long id = jdbc.queryForObject("SELECT id FROM monitored_dbs WHERE name = 'shop'", Long.class);
    // Postgres keeps microseconds; Linux clocks give nanoseconds (CI failed on the difference).
    Instant old = clock.instant().minus(Duration.ofDays(2)).truncatedTo(ChronoUnit.MICROS);
    jdbc.update(
        "INSERT INTO query_cumulative (db_id, queryid, calls, total_exec_time_ms, rows, "
            + "shared_blks_hit, shared_blks_read, captured_at) VALUES (?, 1, 1, 1, 1, 0, 0, ?)",
        id,
        java.sql.Timestamp.from(old));

    mvc.perform(authed(get("/api/v1/databases/shop"), admin))
        .andExpect(jsonPath("$.agent").value("STALE"))
        .andExpect(jsonPath("$.lastIngestAt").value(old.toString()));
  }

  @Test
  void rotatingTheTokenCutsTheOldOneOffAtOnce() throws Exception {
    String old = JsonPath.read(content(register("shop")), "$.agentToken");
    String rotated =
        JsonPath.read(content(authed(post("/api/v1/databases/shop/token"), admin)), "$.agentToken");

    assertThat(rotated).isNotEqualTo(old);
    assertThatThrownBy(() -> ingest(old)).hasStackTraceContaining("UNAUTHENTICATED");
    assertThat(ingest(rotated).getAcceptedSamples()).isEqualTo(1);
  }

  @Test
  void namesAreValidatedAndUnique() throws Exception {
    register("shop").andExpect(status().isCreated());
    register("shop").andExpect(status().isConflict());
    register("bad name; rm -rf").andExpect(status().isBadRequest());
    mvc.perform(authed(post("/api/v1/databases/nope/token"), admin))
        .andExpect(status().isNotFound());
  }

  @Test
  void deletingNeedsTheNameRepeatedAndRemovesTheHistory() throws Exception {
    String token = JsonPath.read(content(register("shop")), "$.agentToken");
    ingest(token);
    ingest(token); // a second batch → one delta row in query_stats
    assertThat(jdbc.queryForObject("SELECT count(*) FROM query_stats", Long.class)).isEqualTo(1);

    mvc.perform(authed(delete("/api/v1/databases/shop"), admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("?confirm=")));
    mvc.perform(authed(delete("/api/v1/databases/shop?confirm=shop"), admin))
        .andExpect(status().isNoContent());

    assertThat(jdbc.queryForObject("SELECT count(*) FROM monitored_dbs", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM query_stats", Long.class)).isZero();
  }

  @Test
  void viewersAndApiTokensCanListButNotRegister() throws Exception {
    mvc.perform(
            authed(post("/api/v1/users"), admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"vic\",\"password\":\"viewer password\"}"))
        .andExpect(status().isCreated());
    String viewer = login("vic", "viewer password");
    String apiToken =
        JsonPath.read(
            content(
                authed(post("/api/v1/api-tokens"), admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"ci\"}")),
            "$.token");

    for (String token : new String[] {viewer, apiToken}) {
      mvc.perform(authed(get("/api/v1/databases"), token)).andExpect(status().isOk());
      mvc.perform(
              authed(post("/api/v1/databases"), token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"name\":\"shop\"}"))
          .andExpect(status().isForbidden());
    }
  }

  private long calls; // cumulative, so each batch after the first yields a delta row

  private com.pglens.proto.v1.IngestSummary ingest(String agentToken) throws Exception {
    calls += 10;
    ManagedChannel channel = GrpcTestTls.channel(grpcServer.getPort()).build();
    try {
      SampleBatch batch =
          SampleBatch.newBuilder()
              .setDbName("shop")
              .setAgentSampleEpochMs(System.currentTimeMillis())
              .addSamples(
                  QueryStatSample.newBuilder()
                      .setQueryid(42L)
                      .setTextHash("h")
                      .setCalls(calls)
                      .setTotalExecTimeMs(calls * 2.5)
                      .setRows(calls))
              .build();
      return new IngestClient(channel, agentToken).send(batch, 10);
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private org.springframework.test.web.servlet.ResultActions register(String name)
      throws Exception {
    return mvc.perform(
        authed(post("/api/v1/databases"), admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\"}"));
  }

  private String content(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn().getResponse().getContentAsString();
  }

  private String content(org.springframework.test.web.servlet.ResultActions actions)
      throws Exception {
    return actions.andReturn().getResponse().getContentAsString();
  }

  private String login(String username, String password) throws Exception {
    return JsonPath.read(
        content(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")),
        "$.token");
  }

  private static MockHttpServletRequestBuilder authed(
      MockHttpServletRequestBuilder request, String token) {
    return request.header("Authorization", "Bearer " + token);
  }
}
