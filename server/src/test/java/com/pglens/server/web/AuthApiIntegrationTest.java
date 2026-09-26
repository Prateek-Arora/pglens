package com.pglens.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pglens.server.grpc.GrpcTestTls;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

/** The HTTP API's login, roles, API tokens and sessions against a real metadata DB (ADR-0044). */
@Tag("it")
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
    properties = {
      "pglens.grpc.port=0",
      "pglens.analysis.initial-delay-ms=3600000",
      "pglens.auth.admin-password=" + AuthApiIntegrationTest.ADMIN_PASSWORD,
      "pglens.auth.login-max-failures=3"
    })
class AuthApiIntegrationTest {

  static final String ADMIN_PASSWORD = "correct horse battery";
  private static final String VIEWER_PASSWORD = "viewer password 1";

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
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(Instant.parse("2026-09-26T10:00:00Z"));
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired MutableClock clock;

  @BeforeEach
  void resetToTheBootstrappedAdmin() {
    jdbc.update("DELETE FROM users WHERE username <> 'admin'");
    jdbc.update("DELETE FROM sessions");
    jdbc.update("DELETE FROM api_tokens");
  }

  @Test
  void healthIsPublicAndEverythingElseNeedsAToken() throws Exception {
    mvc.perform(get("/api/v1/health")).andExpect(status().isOk());

    mvc.perform(get("/api/v1/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(jsonPath("$.status").value(401));
    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer pglens_s_not-a-real-token"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void theBootstrappedAdminLogsInAndOnlyTheTokenHashIsStored() throws Exception {
    String token = login("admin", ADMIN_PASSWORD);

    assertThat(token).startsWith("pglens_s_");
    mvc.perform(authed(get("/api/v1/me"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("admin"))
        .andExpect(jsonPath("$.role").value("ADMIN"))
        .andExpect(jsonPath("$.via").value("SESSION"))
        .andExpect(jsonPath("$.authRequired").value(true));
    List<String> stored = jdbc.queryForList("SELECT token_hash FROM sessions", String.class);
    assertThat(stored).hasSize(1).doesNotContain(token);
    assertThat(jdbc.queryForObject("SELECT password_hash FROM users", String.class))
        .startsWith("{bcrypt}");
  }

  @Test
  void aWrongPasswordIsRefusedWithoutSayingWhyAndRepeatedFailuresAreThrottled() throws Exception {
    createUser("carol", VIEWER_PASSWORD, "VIEWER");
    for (int i = 0; i < 3; i++) {
      loginRequest("carol", "wrong password!!")
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.detail").value("Invalid username or password."));
    }

    loginRequest("carol", VIEWER_PASSWORD)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
    loginRequest("nobody", "wrong password!!").andExpect(status().isUnauthorized());
  }

  @Test
  void aViewerReadsButCannotManageUsers() throws Exception {
    createUser("vera", VIEWER_PASSWORD, "VIEWER");
    String viewer = login("vera", VIEWER_PASSWORD);

    mvc.perform(authed(get("/api/v1/me"), viewer)).andExpect(jsonPath("$.role").value("VIEWER"));
    mvc.perform(authed(get("/api/v1/users"), viewer)).andExpect(status().isForbidden());
    mvc.perform(
            authed(post("/api/v1/users"), viewer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"x\",\"password\":\"long enough pw\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void anApiTokenIsReadOnlyEvenForAnAdmin() throws Exception {
    String session = login("admin", ADMIN_PASSWORD);
    String created =
        mvc.perform(
                authed(post("/api/v1/api-tokens"), session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"ci\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String apiToken = JsonPath.read(created, "$.token");
    assertThat(apiToken).startsWith("pglens_a_");

    mvc.perform(authed(get("/api/v1/me"), apiToken))
        .andExpect(jsonPath("$.via").value("API_TOKEN"))
        .andExpect(jsonPath("$.role").value("VIEWER"));
    mvc.perform(authed(get("/api/v1/users"), apiToken)).andExpect(status().isForbidden());
    mvc.perform(
            authed(post("/api/v1/api-tokens"), apiToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"another\"}"))
        .andExpect(status().isForbidden());
    // The owner sees it (name + last use), never the token again.
    mvc.perform(authed(get("/api/v1/api-tokens"), session))
        .andExpect(jsonPath("$[0].name").value("ci"))
        .andExpect(jsonPath("$[0].token").doesNotExist());
    // Revoked → unusable.
    Integer id = JsonPath.read(created, "$.info.id");
    mvc.perform(authed(delete("/api/v1/api-tokens/" + id), session))
        .andExpect(status().isNoContent());
    mvc.perform(authed(get("/api/v1/me"), apiToken)).andExpect(status().isUnauthorized());
  }

  @Test
  void logoutEndsTheSessionAndAnIdleSessionExpires() throws Exception {
    String first = login("admin", ADMIN_PASSWORD);
    mvc.perform(authed(post("/api/v1/auth/logout"), first)).andExpect(status().isNoContent());
    mvc.perform(authed(get("/api/v1/me"), first)).andExpect(status().isUnauthorized());

    String second = login("admin", ADMIN_PASSWORD);
    clock.advance(Duration.ofHours(7));
    mvc.perform(authed(get("/api/v1/me"), second)).andExpect(status().isOk()); // slides the window
    clock.advance(Duration.ofHours(7));
    mvc.perform(authed(get("/api/v1/me"), second)).andExpect(status().isOk());
    clock.advance(Duration.ofHours(9)); // idle past 8 h
    mvc.perform(authed(get("/api/v1/me"), second)).andExpect(status().isUnauthorized());
  }

  @Test
  void aSessionEndsAtItsMaximumAgeEvenWhenUsedConstantly() throws Exception {
    String token = login("admin", ADMIN_PASSWORD);
    for (int hour = 6; hour < 7 * 24; hour += 6) {
      clock.advance(Duration.ofHours(6));
      mvc.perform(authed(get("/api/v1/me"), token)).andExpect(status().isOk());
    }
    clock.advance(Duration.ofHours(6)); // 7 days + 6 h since login

    mvc.perform(authed(get("/api/v1/me"), token)).andExpect(status().isUnauthorized());
  }

  @Test
  void changingYourPasswordEndsYourOtherSessionsButKeepsThisOne() throws Exception {
    createUser("dave", VIEWER_PASSWORD, "VIEWER");
    String laptop = login("dave", VIEWER_PASSWORD);
    String phone = login("dave", VIEWER_PASSWORD);

    mvc.perform(
            authed(put("/api/v1/me/password"), laptop)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\""
                        + VIEWER_PASSWORD
                        + "\",\"newPassword\":\"a brand new password\"}"))
        .andExpect(status().isNoContent());

    mvc.perform(authed(get("/api/v1/me"), laptop)).andExpect(status().isOk());
    mvc.perform(authed(get("/api/v1/me"), phone)).andExpect(status().isUnauthorized());
    loginRequest("dave", "a brand new password").andExpect(status().isOk());
  }

  @Test
  void usersAreValidatedAndTheLastAdminIsProtected() throws Exception {
    String admin = login("admin", ADMIN_PASSWORD);

    mvc.perform(
            authed(post("/api/v1/users"), admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"eve\",\"password\":\"short\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("The password must be at least 12 characters."));
    createUser("eve", VIEWER_PASSWORD, "VIEWER");
    mvc.perform(
            authed(post("/api/v1/users"), admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"EVE\",\"password\":\"" + VIEWER_PASSWORD + "\"}"))
        .andExpect(status().isConflict());
    mvc.perform(authed(delete("/api/v1/users/admin"), admin)).andExpect(status().isConflict());
    mvc.perform(authed(delete("/api/v1/users/eve"), admin)).andExpect(status().isNoContent());
    mvc.perform(authed(delete("/api/v1/users/eve"), admin)).andExpect(status().isNotFound());
  }

  private void createUser(String username, String password, String role) throws Exception {
    mvc.perform(
            authed(post("/api/v1/users"), login("admin", ADMIN_PASSWORD))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\""
                        + username
                        + "\",\"password\":\""
                        + password
                        + "\",\"role\":\""
                        + role
                        + "\"}"))
        .andExpect(status().isCreated());
  }

  private String login(String username, String password) throws Exception {
    String body =
        loginRequest(username, password)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.token");
  }

  private org.springframework.test.web.servlet.ResultActions loginRequest(
      String username, String password) throws Exception {
    return mvc.perform(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
  }

  private static MockHttpServletRequestBuilder authed(
      MockHttpServletRequestBuilder request, String token) {
    return request.header("Authorization", "Bearer " + token);
  }
}
