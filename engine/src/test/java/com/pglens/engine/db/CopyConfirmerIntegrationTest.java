package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pglens.engine.CopyConfirmer;
import com.pglens.engine.PgLensException;
import com.pglens.engine.confirm.ConfirmPlan;
import com.pglens.engine.confirm.ConfirmReport;
import com.pglens.engine.confirm.IndexConfirmation;
import com.pglens.engine.confirm.StatementSource;
import com.pglens.engine.confirm.Verdict;
import com.pglens.engine.confirm.WorkloadStatement;
import com.pglens.engine.model.ConnectionTarget;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code pglens confirm} end to end on a real server (Phase 2.6, ADR-0042). Two databases: {@code
 * prod} (where the "application" ran its statements, so its pg_stat_statements has the report's
 * texts) and {@code copy} (same schema and data, created after an extra table so every OID differs
 * — like a pg_dump restore). The report is built from prod's real pg_stat_statements rows; the
 * statements are replayed on the copy. Covers every verdict, every guard, the read-only replay, the
 * leftover cleanup, and that no statement value reaches the report.
 */
@Tag("it")
@Testcontainers
class CopyConfirmerIntegrationTest {

  @Container static final PostgreSQLContainer DB = MonitoredDbContainer.create();

  private static final String SECRET = "zq-secret-value";
  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private static final String Q_CUSTOMER = "SELECT id, status FROM orders WHERE customer_id = ?";
  private static final String Q_BODY = "SELECT id FROM docs WHERE body = ?";
  private static final String Q_LATEST =
      "SELECT id FROM events WHERE flag = 1 ORDER BY created LIMIT 10";
  private static final String Q_WRITER =
      "SELECT bump(customer_id) FROM orders WHERE customer_id = 7";
  private static final String Q_NOTE = "SELECT id FROM orders WHERE note = 'none'";

  private static final Map<String, Long> QUERY_IDS = new LinkedHashMap<>();
  private static final Map<String, String> TEXTS = new LinkedHashMap<>();
  private static JsonNode report;

  @BeforeAll
  static void setUp() throws Exception {
    try (Connection c = connect(DB.getDatabaseName())) {
      exec(c, "CREATE DATABASE prod");
      exec(c, "CREATE DATABASE copy");
      exec(c, "CREATE ROLE stranger LOGIN PASSWORD 'pw'");
      exec(c, "CREATE DATABASE bare"); // marked, but without pg_stat_statements
      exec(c, "ALTER DATABASE bare SET pglens.scratch = 'on'");
    }
    try (Connection prod = connect("prod")) {
      exec(prod, "CREATE EXTENSION pg_stat_statements");
      schema(prod);
    }
    try (Connection copy = connect("copy")) {
      exec(copy, "CREATE EXTENSION pg_stat_statements");
      exec(copy, "CREATE TABLE filler (x int)"); // shifts every OID: queryids differ from prod's
      schema(copy);
      exec(copy, "GRANT CONNECT ON DATABASE copy TO stranger");
      // Like the demo server: with track = all, PgLens's own EXPLAIN records the inner query as a
      // nested pg_stat_statements row under the statement's queryid — matching must ignore it.
      exec(copy, "ALTER DATABASE copy SET pg_stat_statements.track = 'all'");
    }

    // The "application" runs on prod: extended protocol with a bound value, and literal statements.
    try (Connection prod = connect("prod")) {
      exec(prod, "SELECT pg_stat_statements_reset()");
      try (PreparedStatement ps = prod.prepareStatement(Q_CUSTOMER)) {
        ps.setInt(1, 42);
        ps.executeQuery().close();
      }
      try (PreparedStatement ps = prod.prepareStatement(Q_BODY)) {
        ps.setString(1, SECRET);
        ps.executeQuery().close();
      }
      exec(prod, Q_LATEST);
      exec(prod, Q_WRITER);
      exec(prod, Q_NOTE);
      for (String q : List.of(Q_CUSTOMER, Q_BODY, Q_LATEST, Q_WRITER, Q_NOTE)) {
        String like = q.replace("?", "$1").replace("= 7", "= $1").replace("'none'", "$1");
        like = like.replace("flag = 1", "flag = $1").replace("LIMIT 10", "LIMIT $2");
        try (PreparedStatement ps =
            prod.prepareStatement(
                "SELECT queryid, query FROM pg_stat_statements WHERE query = ?"
                    + " AND dbid = (SELECT oid FROM pg_database WHERE datname = 'prod')")) {
          ps.setString(1, like);
          try (ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("prod pgss has " + like).isTrue();
            QUERY_IDS.put(q, rs.getLong(1));
            TEXTS.put(q, rs.getString(2));
          }
        }
      }
    }
    report = new ObjectMapper().readTree(reportJson());
  }

  // Same schema + data in both databases.
  private static void schema(Connection c) throws SQLException {
    exec(c, "SELECT setseed(0.42)");
    exec(
        c,
        "CREATE TABLE orders (id serial PRIMARY KEY, customer_id int NOT NULL, status text,"
            + " note text, touched int NOT NULL DEFAULT 0)");
    exec(
        c,
        "INSERT INTO orders (customer_id, status, note) SELECT g % 20000, 'new', 'n' || g"
            + " FROM generate_series(1, 300000) g");
    // A B-tree can't hold these: ~16 kB of random hex each, still > 2 kB compressed (B17).
    exec(c, "CREATE TABLE docs (id serial PRIMARY KEY, body text)");
    exec(c, "INSERT INTO docs (body) SELECT 'short ' || g FROM generate_series(1, 20000) g");
    exec(
        c,
        "INSERT INTO docs (body) SELECT (SELECT string_agg(md5(random()::text || g || i), '')"
            + " FROM generate_series(1, 500) i) FROM generate_series(1, 5) g");
    // The classic slowdown: flag = 1 is 5 % of rows, but all of them have the highest `created`,
    // so an index on created makes "ORDER BY created LIMIT 10" walk ~95 % of the table in random
    // heap order — while the planner, assuming flag = 1 is spread evenly, expects 200 rows.
    exec(c, "CREATE TABLE events (id serial PRIMARY KEY, created int NOT NULL, flag int NOT NULL)");
    exec(
        c,
        "INSERT INTO events (created, flag) SELECT r, CASE WHEN r > 285000 THEN 1 ELSE 0 END"
            + " FROM (SELECT g AS r FROM generate_series(1, 300000) g ORDER BY random()) s");
    exec(
        c,
        "CREATE FUNCTION bump(int) RETURNS int LANGUAGE sql AS"
            + " $$ UPDATE orders SET touched = touched + 1 WHERE customer_id = $1 RETURNING 1 $$");
    exec(c, "ANALYZE");
  }

  private static String reportJson() {
    StringBuilder q = new StringBuilder();
    StringBuilder top = new StringBuilder();
    String[][] recs = {
      {Q_CUSTOMER, "orders", "customer_id", "0.99"},
      {Q_BODY, "docs", "body", "0.98"},
      {Q_LATEST, "events", "created", "0.97"},
      {Q_WRITER, "orders", "touched", "0.5"},
      {Q_NOTE, "orders", "status", "0.4"}, // never run in the workload given to confirm
    };
    for (String[] r : recs) {
      String cand =
          "{\"table\":\"%s\",\"columns\":[\"%s\"],\"accessMethod\":\"BTREE\"}"
              .formatted(r[1], r[2]);
      q.append(q.isEmpty() ? "" : ",")
          .append(
              "{\"queryId\":%d,\"normalizedText\":%s,\"truncated\":false,\"recommendations\":[{\"candidate\":%s,\"validation\":{\"status\":\"PLANNER_VALIDATED\",\"relativeDelta\":%s}}]}"
                  .formatted(QUERY_IDS.get(r[0]), quote(TEXTS.get(r[0])), cand, r[3]));
      top.append(top.isEmpty() ? "" : ",")
          .append(
              "{\"queryId\":%d,\"actionable\":true,\"recommendation\":{\"candidate\":%s}}"
                  .formatted(QUERY_IDS.get(r[0]), cand));
    }
    return ("{\"schemaVersion\":\"1.2\",\"generatedAt\":\"2026-09-25T00:00:00Z\","
            + "\"target\":{\"host\":\"%s\",\"port\":%d,\"database\":\"prod\",\"serverVersion\":\"16\"},"
            + "\"queries\":[%s],\"topRecommendations\":[%s]}")
        .formatted(DB.getHost(), DB.getMappedPort(5432), q, top);
  }

  /** The user's statements for the copy: a bound value (as logged) plus literal statements. */
  private static StatementSource.Parsed workload() {
    List<WorkloadStatement> s = new ArrayList<>();
    s.add(new WorkloadStatement(Q_CUSTOMER.replace("?", "$1"), List.of("'42'"), "app.log:1"));
    s.add(new WorkloadStatement(Q_CUSTOMER.replace("?", "$1"), List.of("'4242'"), "app.log:2"));
    s.add(
        new WorkloadStatement(Q_BODY.replace("?", "$1"), List.of("'" + SECRET + "'"), "app.log:3"));
    s.add(new WorkloadStatement(Q_LATEST, List.of(), "q.sql #1"));
    s.add(new WorkloadStatement(Q_WRITER, List.of(), "q.sql #2"));
    s.add(new WorkloadStatement("SELECT * FROM no_such_table", List.of(), "q.sql #3"));
    return new StatementSource.Parsed(s, 1, 0);
  }

  private static CopyConfirmer.Options options(boolean dryRun) {
    return new CopyConfirmer.Options(10, 5, 3, TIMEOUT, TIMEOUT, dryRun);
  }

  @Test
  void refusesACopyThatIsntMarkedOrIsMarkedOnlyByTheConnection() throws Exception {
    try (Connection c = connect("copy")) {
      exec(c, "ALTER DATABASE copy RESET pglens.scratch");
    }
    ConfirmPlan plan = ConfirmPlan.from(report, 10);
    assertThatThrownBy(
            () ->
                CopyConfirmer.confirm(plan, workload(), target("copy", ""), options(true), s -> {}))
        .isInstanceOf(PgLensException.class)
        .hasMessageContaining("isn't marked as a scratch copy")
        .hasMessageContaining("ALTER DATABASE copy SET pglens.scratch = 'on'");
    // A connection option sets the value for the session, but isn't the database's own marker.
    assertThatThrownBy(
            () ->
                CopyConfirmer.confirm(
                    plan,
                    workload(),
                    target("copy", "?options=-c%20pglens.scratch%3Don"),
                    options(true),
                    s -> {}))
        .isInstanceOf(PgLensException.class)
        .hasMessageContaining("isn't marked");
  }

  @Test
  void refusesTheScannedDatabaseNoPgStatStatementsAMissingTableAndANonOwner() throws Exception {
    try (Connection c = connect("prod")) {
      exec(c, "ALTER DATABASE prod SET pglens.scratch = 'on'"); // even marked, it's the scanned DB
    }
    try (Connection c = connect("copy")) {
      exec(c, "ALTER DATABASE copy SET pglens.scratch = 'on'");
    }
    try {
      ConfirmPlan plan = ConfirmPlan.from(report, 10);
      assertThatThrownBy(
              () ->
                  CopyConfirmer.confirm(
                      plan, workload(), target("prod", ""), options(true), s -> {}))
          .isInstanceOf(PgLensException.class)
          .hasMessageContaining("the database the scan report came from");

      JsonNode renamed =
          new ObjectMapper()
              .readTree(report.toString().replace("\"table\":\"docs\"", "\"table\":\"docz\""));
      assertThatThrownBy(
              () ->
                  CopyConfirmer.confirm(
                      ConfirmPlan.from(renamed, 10),
                      workload(),
                      target("copy", ""),
                      options(true),
                      s -> {}))
          .isInstanceOf(PgLensException.class)
          .hasMessageContaining("don't exist")
          .hasMessageContaining("docz");

      assertThatThrownBy(
              () ->
                  CopyConfirmer.confirm(
                      plan, workload(), target("bare", ""), options(true), s -> {}))
          .isInstanceOf(PgLensException.class)
          .hasMessageContaining("pg_stat_statements isn't installed")
          .hasMessageContaining("CREATE EXTENSION pg_stat_statements");

      ConnectionTarget stranger =
          new ConnectionTarget(jdbcUrl("copy", ""), "stranger", "pw", "copy");
      assertThatThrownBy(
              () -> CopyConfirmer.confirm(plan, workload(), stranger, options(true), s -> {}))
          .isInstanceOf(PgLensException.class)
          .hasMessageContaining("needs the table's owner");
    } finally {
      try (Connection c = connect("prod")) {
        exec(c, "ALTER DATABASE prod RESET pglens.scratch");
      }
    }
  }

  @Test
  void measuresEveryIndexOnTheMarkedCopyAndLeavesNothingBehind() throws Exception {
    try (Connection c = connect("copy")) {
      exec(c, "ALTER DATABASE copy SET pglens.scratch = 'on'");
      exec(c, "CREATE INDEX pglens_confirm_99 ON orders (note)"); // left by an "interrupted" run
    }
    ConfirmPlan plan = ConfirmPlan.from(report, 10);

    ConfirmReport dry =
        CopyConfirmer.confirm(plan, workload(), target("copy", ""), options(true), s -> {});
    assertThat(dry.dryRun()).isTrue();
    assertThat(dry.indexes()).allMatch(i -> i.verdict() == null && i.buildMs() == null);
    assertThat(dry.indexes().get(0).queries().get(0).statements()).isEqualTo(2);
    assertThat(pglensIndexes()).containsExactly("pglens_confirm_99"); // a dry run changes nothing

    ConfirmReport r =
        CopyConfirmer.confirm(plan, workload(), target("copy", ""), options(false), s -> {});

    Map<String, IndexConfirmation> byTable = new LinkedHashMap<>();
    r.indexes().forEach(i -> byTable.put(i.table() + "." + i.columns().get(0), i));
    IndexConfirmation customer = byTable.get("orders.customer_id");
    assertThat(customer.verdict()).isEqualTo(Verdict.FASTER);
    assertThat(customer.measuredDrop()).isGreaterThan(0.5);
    assertThat(customer.queries().get(0).statements()).isEqualTo(2);
    assertThat(customer.buildMs()).isNotNull();
    assertThat(customer.indexBytes()).isPositive();

    IndexConfirmation body = byTable.get("docs.body");
    assertThat(body.verdict()).isEqualTo(Verdict.UNBUILDABLE);
    assertThat(body.reason()).contains("index row");

    IndexConfirmation latest = byTable.get("events.created");
    assertThat(latest.verdict()).as("an index the planner misuses").isEqualTo(Verdict.SLOWER);
    assertThat(latest.queries().get(0).estimatedDrop()).isEqualTo(0.97);

    IndexConfirmation writer = byTable.get("orders.touched");
    assertThat(writer.verdict()).isEqualTo(Verdict.NOT_MEASURED);

    IndexConfirmation unmatched = byTable.get("orders.status");
    assertThat(unmatched.verdict()).isEqualTo(Verdict.NOT_MEASURED);
    assertThat(unmatched.reason()).contains("no statement");

    // The write was rejected: nothing changed on the copy except PgLens's own indexes, now gone.
    try (Connection c = connect("copy");
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT sum(touched) FROM orders")) {
      rs.next();
      assertThat(rs.getLong(1)).isZero();
    }
    assertThat(pglensIndexes()).isEmpty();
    assertThat(r.notes()).anyMatch(n -> n.contains("pglens_confirm_99"));
    assertThat(r.notes()).anyMatch(n -> n.contains("median of 3 runs"));
    assertThat(r.statements().unusable()).isEqualTo(2); // no_such_table + the writer
    assertThat(r.statements().shapesMatched()).isEqualTo(3);

    // No statement value or text reaches the report.
    String json = new ObjectMapper().writeValueAsString(r);
    assertThat(json).doesNotContain(SECRET).doesNotContain("4242").doesNotContain("FROM orders");
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static List<String> pglensIndexes() throws SQLException {
    List<String> out = new ArrayList<>();
    try (Connection c = connect("copy");
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT relname FROM pg_class WHERE relkind = 'i' AND relname LIKE 'pglens%'")) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  private static ConnectionTarget target(String db, String query) {
    return new ConnectionTarget(jdbcUrl(db, query), DB.getUsername(), DB.getPassword(), db);
  }

  private static String jdbcUrl(String db, String query) {
    return "jdbc:postgresql://%s:%d/%s%s"
        .formatted(DB.getHost(), DB.getMappedPort(5432), db, query);
  }

  private static Connection connect(String db) throws SQLException {
    return DriverManager.getConnection(jdbcUrl(db, ""), DB.getUsername(), DB.getPassword());
  }

  private static void exec(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }
}
