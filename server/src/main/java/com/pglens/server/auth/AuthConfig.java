package com.pglens.server.auth;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** Wires {@link AuthService}: settings, a clock, the first-admin bootstrap and session pruning. */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {

  private static final Logger log = LoggerFactory.getLogger(AuthConfig.class);

  @Bean
  @ConditionalOnMissingBean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * On an empty {@code users} table, creates the first admin. A generated password is logged once —
   * the Grafana/Jenkins pattern — because there is no other channel on a fresh install; set {@code
   * PGLENS_ADMIN_PASSWORD} to avoid that.
   */
  @Bean
  ApplicationRunner adminBootstrap(AuthService auth) {
    return args -> {
      AuthProperties props = auth.properties();
      if (props.disabled()) {
        log.warn(
            "AUTHENTICATION IS DISABLED (pglens.auth.mode=none): anyone who can reach the HTTP"
                + " API can read every query and register databases. Use only for one person on"
                + " their own machine.");
      }
      auth.bootstrapAdmin()
          .ifPresent(
              password ->
                  log.warn(
                      "Created the first admin user '{}' with the generated password: {}  —"
                          + " log in and change it (or set PGLENS_ADMIN_PASSWORD before the"
                          + " first start). This is shown only once.",
                      props.adminUsername(),
                      password));
    };
  }

  @Bean
  SessionPruner sessionPruner(AuthService auth) {
    return new SessionPruner(auth);
  }

  /** Deletes expired and idle sessions hourly, so the table can't grow without bound. */
  static class SessionPruner {
    private final AuthService auth;

    SessionPruner(AuthService auth) {
      this.auth = auth;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    void prune() {
      int removed = auth.pruneSessions();
      if (removed > 0) {
        log.info("pruned {} expired or idle login sessions", removed);
      }
    }
  }
}
