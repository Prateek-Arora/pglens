package com.pglens.server.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads/writes the {@code monitored_dbs} registry and resolves a presented agent token to a db. */
@Repository
public class MonitoredDbRepository {

  private final JdbcTemplate jdbc;

  public MonitoredDbRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Every registered monitored database — the scheduled analysis job iterates these (ADR-0028). */
  public java.util.List<MonitoredDb> findAll() {
    return jdbc.query(
        "SELECT id, name FROM monitored_dbs ORDER BY id",
        (rs, n) -> new MonitoredDb(rs.getLong("id"), rs.getString("name")));
  }

  /** Resolves a hashed agent token to its monitored database, or empty if the token is unknown. */
  public Optional<MonitoredDb> findByTokenHash(String tokenHash) {
    return jdbc
        .query(
            "SELECT id, name FROM monitored_dbs WHERE agent_token_hash = ?",
            (rs, n) -> new MonitoredDb(rs.getLong("id"), rs.getString("name")),
            tokenHash)
        .stream()
        .findFirst();
  }

  /** Registers a monitored database (bootstrap/admin path) and returns its new id. */
  public long register(String name, String host, String agentTokenHash) {
    return jdbc.queryForObject(
        "INSERT INTO monitored_dbs (name, host, agent_token_hash) VALUES (?, ?, ?) RETURNING id",
        Long.class,
        name,
        host,
        agentTokenHash);
  }

  /**
   * The last global {@code stats_reset} the server recorded for this db (empty until first seen).
   */
  public Optional<Instant> lastStatsReset(long dbId) {
    OffsetDateTime ts =
        jdbc.queryForObject(
            "SELECT last_stats_reset FROM monitored_dbs WHERE id = ?", OffsetDateTime.class, dbId);
    return Optional.ofNullable(ts).map(OffsetDateTime::toInstant);
  }

  /**
   * Records the global {@code stats_reset} observed for this db (for cross-batch reset detection).
   */
  public void updateLastStatsReset(long dbId, Instant statsReset) {
    jdbc.update(
        "UPDATE monitored_dbs SET last_stats_reset = ? WHERE id = ?",
        OffsetDateTime.ofInstant(statsReset, ZoneOffset.UTC),
        dbId);
  }
}
