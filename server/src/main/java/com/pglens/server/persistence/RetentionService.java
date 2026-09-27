package com.pglens.server.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Bounds PgLens's own store (B26, ADR-0053). Every agent interval appends a row per query, per
 * index and per table; left alone that is gigabytes a year. Rows older than {@code raw-days}
 * (default 35: the longest dashboard window, 30 days, plus margin) are deleted from the
 * per-interval series. The hourly rollup {@code query_stats_hourly} — which the windows, trends and
 * before/after comparisons read — is kept. Its trigger fires on INSERT only, so deleting raw rows
 * leaves it exact. Hygiene's "unused" and the write load then describe the last {@code raw-days}.
 */
@Service
public class RetentionService {

  private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Duration keep;

  public RetentionService(
      JdbcTemplate jdbc, Clock clock, @Value("${pglens.retention.raw-days:35}") int rawDays) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.keep = kept(rawDays);
  }

  /** How long per-sample rows are kept for a configured {@code raw-days}: at least 31 days. */
  public static Duration kept(int rawDays) {
    return Duration.ofDays(Math.max(rawDays, 31));
  }

  @Scheduled(
      fixedDelayString = "${pglens.retention.interval-ms:3600000}",
      initialDelayString = "${pglens.retention.initial-delay-ms:600000}")
  public void runScheduled() {
    try {
      prune();
    } catch (RuntimeException e) {
      log.warn("retention pass failed: {}", e.toString());
    }
  }

  /** Deletes per-interval rows older than the retention window; returns how many. */
  public int prune() {
    OffsetDateTime cutoff = OffsetDateTime.ofInstant(clock.instant().minus(keep), ZoneOffset.UTC);
    int deleted = 0;
    for (String table : new String[] {"query_stats", "index_stats", "table_stats"}) {
      deleted += jdbc.update("DELETE FROM " + table + " WHERE captured_at < ?", cutoff);
    }
    if (deleted > 0) {
      log.info("retention: deleted {} per-interval row(s) older than {}", deleted, cutoff);
    }
    return deleted;
  }
}
