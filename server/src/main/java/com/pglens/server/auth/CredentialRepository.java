package com.pglens.server.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bearer credentials for people and scripts (V9): login {@code sessions} and {@code api_tokens}.
 * Every lookup is by the token's SHA-256 hash; the token itself is never stored.
 */
@Repository
class CredentialRepository {

  private final JdbcTemplate jdbc;

  CredentialRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * All session times come from the service's clock, never the database's {@code now()}: expiry is
   * checked against the service clock, and the two can differ (another host, or a test clock).
   */
  void createSession(String tokenHash, long userId, Instant now, Instant expiresAt) {
    jdbc.update(
        "INSERT INTO sessions (token_hash, user_id, created_at, last_used_at, expires_at)"
            + " VALUES (?, ?, ?, ?, ?)",
        tokenHash,
        userId,
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(expiresAt));
  }

  /** The user of a live session: not past its absolute expiry and used within {@code idleSince}. */
  Optional<User> findLiveSession(String tokenHash, Instant now, Instant idleSince) {
    return jdbc
        .query(
            "SELECT u.id, u.username, u.role, u.created_at FROM sessions s"
                + " JOIN users u ON u.id = s.user_id"
                + " WHERE s.token_hash = ? AND s.expires_at > ? AND s.last_used_at > ?",
            (rs, n) ->
                new User(
                    rs.getLong("id"),
                    rs.getString("username"),
                    Role.valueOf(rs.getString("role")),
                    rs.getTimestamp("created_at").toInstant()),
            tokenHash,
            Timestamp.from(now),
            Timestamp.from(idleSince))
        .stream()
        .findFirst();
  }

  /** Slides the idle window; skipped when touched within the last minute (no write per request). */
  void touchSession(String tokenHash, Instant now) {
    jdbc.update(
        "UPDATE sessions SET last_used_at = ? WHERE token_hash = ? AND last_used_at < ?",
        Timestamp.from(now),
        tokenHash,
        Timestamp.from(now.minusSeconds(60)));
  }

  void deleteSession(String tokenHash) {
    jdbc.update("DELETE FROM sessions WHERE token_hash = ?", tokenHash);
  }

  /** Ends every session of a user except {@code keepTokenHash} (null = all of them). */
  void deleteSessionsOf(long userId, String keepTokenHash) {
    jdbc.update(
        "DELETE FROM sessions WHERE user_id = ? AND token_hash IS DISTINCT FROM ?",
        userId,
        keepTokenHash);
  }

  /** Removes sessions past either limit; returns how many. */
  int pruneSessions(Instant now, Instant idleSince) {
    return jdbc.update(
        "DELETE FROM sessions WHERE expires_at <= ? OR last_used_at <= ?",
        Timestamp.from(now),
        Timestamp.from(idleSince));
  }

  /** Inserts an API token; returns empty if the owner already has one with this name. */
  Optional<ApiToken> createApiToken(long userId, String name, String tokenHash) {
    return jdbc
        .query(
            "INSERT INTO api_tokens (user_id, name, token_hash) VALUES (?, ?, ?)"
                + " ON CONFLICT (user_id, name) DO NOTHING"
                + " RETURNING id, name, created_at, last_used_at",
            (rs, n) -> apiToken(rs),
            userId,
            name,
            tokenHash)
        .stream()
        .findFirst();
  }

  List<ApiToken> apiTokensOf(long userId) {
    return jdbc.query(
        "SELECT id, name, created_at, last_used_at FROM api_tokens WHERE user_id = ? ORDER BY id",
        (rs, n) -> apiToken(rs),
        userId);
  }

  /** Deletes the owner's token; false if it doesn't exist or belongs to someone else. */
  boolean deleteApiToken(long userId, long tokenId) {
    return jdbc.update("DELETE FROM api_tokens WHERE id = ? AND user_id = ?", tokenId, userId) == 1;
  }

  Optional<User> findApiTokenOwner(String tokenHash) {
    return jdbc
        .query(
            "SELECT u.id, u.username, u.role, u.created_at FROM api_tokens t"
                + " JOIN users u ON u.id = t.user_id WHERE t.token_hash = ?",
            (rs, n) ->
                new User(
                    rs.getLong("id"),
                    rs.getString("username"),
                    Role.valueOf(rs.getString("role")),
                    rs.getTimestamp("created_at").toInstant()),
            tokenHash)
        .stream()
        .findFirst();
  }

  void touchApiToken(String tokenHash, Instant now) {
    jdbc.update(
        "UPDATE api_tokens SET last_used_at = ? WHERE token_hash = ?"
            + " AND (last_used_at IS NULL OR last_used_at < ?)",
        Timestamp.from(now),
        tokenHash,
        Timestamp.from(now.minusSeconds(60)));
  }

  private static ApiToken apiToken(java.sql.ResultSet rs) throws java.sql.SQLException {
    Timestamp lastUsed = rs.getTimestamp("last_used_at");
    return new ApiToken(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getTimestamp("created_at").toInstant(),
        lastUsed == null ? null : lastUsed.toInstant());
  }
}
