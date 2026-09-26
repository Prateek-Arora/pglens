package com.pglens.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pglens.server.grpc.GrpcTestTls;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@code pglens.auth.mode=none} (ADR-0044): one local user, every response flagged, no API tokens.
 * Also the no-password bootstrap: a first admin still exists for when login is turned on.
 */
@Tag("it")
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
    properties = {
      "pglens.grpc.port=0",
      "pglens.analysis.initial-delay-ms=3600000",
      "pglens.auth.mode=none"
    })
class AuthDisabledIntegrationTest {

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

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  void everyRequestIsTheLocalAdminAndIsFlagged() throws Exception {
    mvc.perform(get("/api/v1/me"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-PgLens-Auth", "none"))
        .andExpect(jsonPath("$.via").value("AUTH_DISABLED"))
        .andExpect(jsonPath("$.role").value("ADMIN"))
        .andExpect(jsonPath("$.authRequired").value(false));
  }

  @Test
  void apiTokensNeedARealUser() throws Exception {
    mvc.perform(
            post("/api/v1/api-tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ci\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void aFirstAdminWithAGeneratedPasswordExistsForWhenLoginIsTurnedOn() {
    assertThat(jdbc.queryForList("SELECT role FROM users", String.class)).containsExactly("ADMIN");
    assertThat(jdbc.queryForObject("SELECT password_hash FROM users", String.class))
        .startsWith("{bcrypt}");
  }
}
