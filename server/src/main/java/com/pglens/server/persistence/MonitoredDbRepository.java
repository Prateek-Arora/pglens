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
   * A registered database as the HTTP API shows it (ADR-0044). {@code lastIngestAt} is when the
   * server last accepted a batch from its agent; null = the agent has never connected.
   */
  public record Registration(
      long id, String name, String host, Instant createdAt, Instant lastIngestAt) {}

  /**
   * {@code last_ingest_at} arrived in V9; for a database whose history predates it, the newest
   * cumulative's receive time is the same fact (an ingest wrote it), so an upgraded install doesn't
   * show "never connected" over days of history.
   */
  private static final String REGISTRATION_COLUMNS =
      "id, name, host, created_at, COALESCE(last_ingest_at, (SELECT max(c.captured_at) "
          + "FROM query_cumulative c WHERE c.db_id = m.id)) AS last_ingest_at";

  public java.util.List<Registration> registrations() {
    return jdbc.query(
        "SELECT " + REGISTRATION_COLUMNS + " FROM monitored_dbs m ORDER BY name",
        (rs, n) -> registration(rs));
  }

  public Optional<Registration> registration(String name) {
    return jdbc
        .query(
            "SELECT " + REGISTRATION_COLUMNS + " FROM monitored_dbs m WHERE name = ?",
            (rs, n) -> registration(rs),
            name)
        .stream()
        .findFirst();
  }

  /** Registers a database unless the name is taken (then empty). */
  public Optional<Registration> registerIfAbsent(String name, String agentTokenHash) {
    return jdbc
        .query(
            "INSERT INTO monitored_dbs (name, agent_token_hash) VALUES (?, ?)"
                + " ON CONFLICT (name) DO NOTHING"
                + " RETURNING id, name, host, created_at, last_ingest_at",
            (rs, n) -> registration(rs),
            name,
            agentTokenHash)
        .stream()
        .findFirst();
  }

  /** Replaces the agent token; the old one stops working at once. False if the name is unknown. */
  public boolean replaceToken(String name, String agentTokenHash) {
    return jdbc.update(
            "UPDATE monitored_dbs SET agent_token_hash = ? WHERE name = ?", agentTokenHash, name)
        == 1;
  }

  /**
   * Deletes the database and, by {@code ON DELETE CASCADE}, all of its history in this metadata
   * store. Never touches the monitored database itself. False if the name is unknown.
   */
  public boolean delete(String name) {
    return jdbc.update("DELETE FROM monitored_dbs WHERE name = ?", name) == 1;
  }

  /** Called once per accepted ingest batch, in the batch's transaction. */
  public void recordIngest(long dbId, Instant at) {
    jdbc.update(
        "UPDATE monitored_dbs SET last_ingest_at = ? WHERE id = ?",
        OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
        dbId);
  }

  private static Registration registration(java.sql.ResultSet rs) throws java.sql.SQLException {
    java.sql.Timestamp last = rs.getTimestamp("last_ingest_at");
    return new Registration(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("host"),
        rs.getTimestamp("created_at").toInstant(),
        last == null ? null : last.toInstant());
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
