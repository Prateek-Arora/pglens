package com.pglens.server.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pglens.auth.*} (ADR-0044). Login is required by default; {@code mode: none} is for one
 * person on their own machine and is flagged on every response and in the dashboard.
 */
@ConfigurationProperties(prefix = "pglens.auth")
public record AuthProperties(
    Mode mode,
    Duration sessionIdleTimeout,
    Duration sessionMaxAge,
    String adminUsername,
    String adminPassword,
    int loginMaxFailures,
    Duration loginLockout) {

  public enum Mode {
    REQUIRED,
    NONE
  }

  public AuthProperties {
    mode = mode == null ? Mode.REQUIRED : mode;
    sessionIdleTimeout = sessionIdleTimeout == null ? Duration.ofHours(8) : sessionIdleTimeout;
    sessionMaxAge = sessionMaxAge == null ? Duration.ofDays(7) : sessionMaxAge;
    adminUsername = adminUsername == null || adminUsername.isBlank() ? "admin" : adminUsername;
    loginMaxFailures = loginMaxFailures <= 0 ? 5 : loginMaxFailures;
    loginLockout = loginLockout == null ? Duration.ofMinutes(15) : loginLockout;
  }

  public boolean disabled() {
    return mode == Mode.NONE;
  }
}
