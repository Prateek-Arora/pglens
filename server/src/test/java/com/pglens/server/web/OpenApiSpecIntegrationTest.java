package com.pglens.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pglens.server.grpc.GrpcTestTls;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The committed API contract (ADR-0046): {@code docs/api/openapi.json} must equal what the server
 * serves, so the dashboard's generated types can't silently drift from the API. After an API
 * change, run {@code make openapi} and commit the file.
 */
@Tag("it")
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {"pglens.grpc.port=0", "pglens.analysis.initial-delay-ms=3600000"})
class OpenApiSpecIntegrationTest {

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

  private static final Path COMMITTED =
      Path.of(System.getProperty("pglens.repoRoot", ".."), "docs", "api", "openapi.json");

  @Autowired MockMvc mvc;

  @Test
  void theCommittedSpecIsWhatTheServerServes() throws Exception {
    String served = canonical(spec());
    if (Boolean.getBoolean("pglens.openapi.update")) {
      Files.createDirectories(COMMITTED.getParent());
      Files.writeString(COMMITTED, served, StandardCharsets.UTF_8);
    }
    assertThat(Files.exists(COMMITTED))
        .as("%s is missing: run `make openapi` and commit it", COMMITTED)
        .isTrue();
    assertThat(Files.readString(COMMITTED, StandardCharsets.UTF_8))
        .as("%s is stale: run `make openapi` and commit it", COMMITTED)
        .isEqualTo(served);
  }

  @Test
  void everyFieldIsRequiredAndOnlyNullableOnesMayBeNull() throws Exception {
    String spec = spec();
    String entry = "$.components.schemas.LeaderboardEntry";
    assertThat(JsonPath.<List<String>>read(spec, entry + ".required"))
        .contains("queryid", "measuredTotalMs", "measuredMeanMs", "recommendation");
    assertThat(JsonPath.<List<String>>read(spec, entry + ".properties.measuredMeanMs.type"))
        .containsExactly("number", "null");
    assertThat(JsonPath.<Object>read(spec, entry + ".properties.measuredTotalMs.type"))
        .isEqualTo("number");
    // A nullable reference to another schema: anyOf [ref, null].
    assertThat(
            JsonPath.<List<Map<String, Object>>>read(
                spec, "$.components.schemas.QueryDetail.properties.plan.anyOf"))
        .hasSize(2);
    assertThat(JsonPath.<Object>read(spec, "$.servers[0].url")).isEqualTo("/");
  }

  private String spec() throws Exception {
    return mvc.perform(get("/api/v1/openapi.json"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  /** Sorted keys and fixed indentation, so the committed file diffs cleanly. */
  private static String canonical(String json) {
    JsonMapper mapper =
        JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    return mapper.writeValueAsString(mapper.readValue(json, Map.class)) + "\n";
  }
}
