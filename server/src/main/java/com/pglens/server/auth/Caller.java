package com.pglens.server.auth;

/**
 * Who is making an HTTP request, resolved once by the bearer-token filter (the single choke point,
 * like the gRPC {@code AuthInterceptor}). An API token is always read-only, whatever its owner's
 * role: a leaked script token must not be able to register databases or create users.
 *
 * @param userId the owning user; {@code null} when authentication is disabled
 */
public record Caller(Long userId, String username, Role userRole, Via via) {

  /** How the caller authenticated. */
  public enum Via {
    SESSION,
    API_TOKEN,
    /** {@code pglens.auth.mode=none}: a single local user, every response flagged. */
    AUTH_DISABLED
  }

  /** The role that applies to this request. */
  public Role role() {
    return via == Via.API_TOKEN ? Role.VIEWER : userRole;
  }

  static Caller authDisabled() {
    return new Caller(null, "local", Role.ADMIN, Via.AUTH_DISABLED);
  }
}
