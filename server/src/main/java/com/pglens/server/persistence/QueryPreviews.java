package com.pglens.server.persistence;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The start of each query's text, as the read API shows it next to a number. */
@Repository
public class QueryPreviews {

  /** Characters of SQL per query, as on the leaderboard. */
  public static final int PREVIEW_CHARS = 300;

  private final JdbcTemplate jdbc;

  public QueryPreviews(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Each query's preview, "…"-terminated when cut; one round trip for all of them. */
  public Map<Long, String> of(long dbId, Collection<Long> queryIds) {
    Map<Long, String> out = new HashMap<>();
    if (queryIds.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT queryid, left(normalized_text, ?) AS preview, length(normalized_text) > ? AS cut "
            + "FROM query_texts WHERE db_id = ? AND queryid = ANY (?)",
        rs -> {
          out.put(
              rs.getLong("queryid"), rs.getString("preview") + (rs.getBoolean("cut") ? "…" : ""));
        },
        PREVIEW_CHARS,
        PREVIEW_CHARS,
        dbId,
        queryIds.stream().distinct().toArray(Long[]::new));
    return out;
  }
}
