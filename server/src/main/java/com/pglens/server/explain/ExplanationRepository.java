package com.pglens.server.explain;

import com.pglens.explain.Explanation;
import com.pglens.explain.Explanation.Source;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code explanations} cache (V8): one row per exact input — facts hash, model and prompt
 * version — so an unchanged index is never sent to the model twice, and a new model or prompt
 * regenerates. A row with {@code retry_after} (a template written because the model was down) is
 * treated as missing once that time has passed.
 */
@Repository
public class ExplanationRepository {

  /** The cache key: everything the explanation was produced from. */
  public record Key(
      long dbId, long queryId, String ddl, String factsHash, String model, String promptVersion) {}

  private final JdbcTemplate jdbc;

  public ExplanationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** True when a usable explanation for exactly this input is cached. */
  public boolean isFresh(Key k, Instant now) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM explanations WHERE db_id = ? AND queryid = ? AND ddl = ? "
                + "AND facts_hash = ? AND model = ? AND prompt_version = ? "
                + "AND (retry_after IS NULL OR retry_after > ?)",
            Integer.class,
            k.dbId(),
            k.queryId(),
            k.ddl(),
            k.factsHash(),
            k.model(),
            k.promptVersion(),
            Timestamp.from(now));
    return n != null && n > 0;
  }

  public void put(Key k, Explanation e, Instant retryAfter) {
    jdbc.update(
        "INSERT INTO explanations (db_id, queryid, ddl, facts_hash, model, prompt_version, source, "
            + "summary, why_it_is_slow, what_changes, fallback_reason, violations, docs, retry_after) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (db_id, queryid, ddl, facts_hash, model, prompt_version) DO UPDATE SET "
            + "source = excluded.source, summary = excluded.summary, "
            + "why_it_is_slow = excluded.why_it_is_slow, what_changes = excluded.what_changes, "
            + "fallback_reason = excluded.fallback_reason, violations = excluded.violations, "
            + "docs = excluded.docs, retry_after = excluded.retry_after, created_at = now()",
        ps -> {
          ps.setLong(1, k.dbId());
          ps.setLong(2, k.queryId());
          ps.setString(3, k.ddl());
          ps.setString(4, k.factsHash());
          ps.setString(5, k.model());
          ps.setString(6, k.promptVersion());
          ps.setString(7, e.source().name());
          ps.setString(8, e.summary());
          ps.setString(9, e.whyItIsSlow());
          ps.setString(10, e.whatTheIndexChanges());
          ps.setString(11, e.fallbackReason());
          ps.setArray(12, ps.getConnection().createArrayOf("text", e.violations().toArray()));
          ps.setArray(13, ps.getConnection().createArrayOf("text", e.docs().toArray()));
          ps.setTimestamp(14, retryAfter == null ? null : Timestamp.from(retryAfter));
        });
  }

  /** A cached LLM explanation and what produced it. */
  public record CachedLlm(Explanation explanation, String promptVersion, Instant createdAt) {}

  /**
   * The newest LLM-written explanation for exactly these facts — the read API shows it instead of
   * the template (ADR-0044). Facts that changed since (new stats, a new plan) never match, so a
   * stale explanation is never shown.
   */
  public Optional<CachedLlm> cachedLlm(long dbId, long queryId, String ddl, String factsHash) {
    return jdbc
        .query(
            "SELECT ddl, summary, why_it_is_slow, what_changes, model, docs, prompt_version, "
                + "created_at FROM explanations WHERE db_id = ? AND queryid = ? AND ddl = ? "
                + "AND facts_hash = ? AND source = 'LLM' ORDER BY created_at DESC LIMIT 1",
            (rs, n) ->
                new CachedLlm(
                    new Explanation(
                        rs.getString(1),
                        Source.LLM,
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        null,
                        List.of(),
                        strings(rs.getArray(6))),
                    rs.getString(7),
                    rs.getTimestamp(8).toInstant()),
            dbId,
            queryId,
            ddl,
            factsHash)
        .stream()
        .findFirst();
  }

  /** The newest explanation for an index — what the Phase 4 dashboard will show. */
  public Optional<Explanation> latest(long dbId, String ddl) {
    return jdbc
        .query(
            "SELECT ddl, source, summary, why_it_is_slow, what_changes, model, fallback_reason, "
                + "violations, docs FROM explanations WHERE db_id = ? AND ddl = ? "
                + "ORDER BY created_at DESC LIMIT 1",
            (rs, n) ->
                new Explanation(
                    rs.getString(1),
                    Source.valueOf(rs.getString(2)),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    Source.valueOf(rs.getString(2)) == Source.LLM ? rs.getString(6) : null,
                    rs.getString(7),
                    strings(rs.getArray(8)),
                    strings(rs.getArray(9))),
            dbId,
            ddl)
        .stream()
        .findFirst();
  }

  private static List<String> strings(Array a) throws SQLException {
    return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
  }
}
