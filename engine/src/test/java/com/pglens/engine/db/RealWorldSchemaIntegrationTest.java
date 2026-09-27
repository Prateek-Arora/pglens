package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.PgLensEngine;
import com.pglens.engine.model.ConnectionTarget;
import com.pglens.engine.model.QueryReport;
import com.pglens.engine.model.RankBy;
import com.pglens.engine.model.Recommendation;
import com.pglens.engine.model.ScanReport;
import com.pglens.engine.model.SqlIdent;
import com.pglens.engine.model.ValidationResult.Status;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A schema shaped like real applications rather than the demo (ADR-0049), scanned by a
 * least-privilege role the way the README sets one up: tables outside {@code public}, Prisma-style
 * quoted camelCase names, a table and a column named with reserved words, {@code varchar} columns,
 * and a partitioned table. Every planner-validated recommendation must be SQL that Postgres runs as
 * written — the copy-paste promise — and a role without access must get the grants that fix it, not
 * a misleading "can't be explained".
 */
@Tag("it")
@Testcontainers
class RealWorldSchemaIntegrationTest {

  @Container static final PostgreSQLContainer DB = MonitoredDbContainer.create();

  private static final String DB_NAME = "pglens_demo";
  private static final String ROLE = "advisor";

  private static JdbcTemplate su;

  @BeforeAll
  static void createSchemaAndWorkload() {
    su =
        new JdbcTemplate(
            new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
    su.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
    su.execute("CREATE EXTENSION IF NOT EXISTS hypopg");
    su.execute("CREATE SCHEMA app");
    su.execute("CREATE SCHEMA billing");
    su.execute(
        "CREATE TABLE app.\"UserAccounts\" (id bigint PRIMARY KEY, \"Email\" varchar(200),"
            + " status varchar(20), tenant_id int)");
    su.execute(
        "INSERT INTO app.\"UserAccounts\" SELECT g, 'user' || g || '@example.com',"
            + " (ARRAY['active','invited','disabled'])[1 + g % 3], g % 500"
            + " FROM generate_series(1, 60000) g");
    su.execute(
        "CREATE TABLE \"Post\" (id bigint PRIMARY KEY, \"authorId\" bigint, title text,"
            + " \"createdAt\" timestamptz)");
    su.execute(
        "INSERT INTO \"Post\" SELECT g, 1 + g % 10000, 'post ' || g,"
            + " now() - (g || ' minutes')::interval FROM generate_series(1, 100000) g");
    su.execute("CREATE TABLE \"order\" (id bigint PRIMARY KEY, \"user\" bigint, note text)");
    su.execute(
        "INSERT INTO \"order\" SELECT g, 1 + g % 5000, 'n' FROM generate_series(1, 60000) g");
    su.execute(
        "CREATE TABLE billing.invoices (id bigint, acct bigint, amount int, d date)"
            + " PARTITION BY RANGE (d)");
    su.execute(
        "CREATE TABLE billing.invoices_2025 PARTITION OF billing.invoices"
            + " FOR VALUES FROM ('2025-01-01') TO ('2026-01-01')");
    su.execute(
        "CREATE TABLE billing.invoices_2026 PARTITION OF billing.invoices"
            + " FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')");
    su.execute(
        "INSERT INTO billing.invoices SELECT g, 1 + g % 5000, g % 1000,"
            + " date '2025-01-01' + g % 700 FROM generate_series(1, 100000) g");
    su.execute("ANALYZE");
    su.execute("SELECT pg_stat_statements_reset()");

    for (int i = 0; i < 25; i++) {
      su.queryForList(
          "SELECT id FROM app.\"UserAccounts\" WHERE \"Email\" = 'user" + i + "@example.com'");
      su.queryForList("SELECT id FROM app.\"UserAccounts\" WHERE tenant_id = " + i);
      su.queryForList(
          "SELECT id, title FROM \"Post\" WHERE \"authorId\" = "
              + i
              + " ORDER BY \"createdAt\" DESC LIMIT 10");
      su.queryForList("SELECT o.id FROM \"order\" o WHERE o.\"user\" = " + i);
      su.queryForList("SELECT sum(amount) FROM billing.invoices WHERE acct = " + i);
    }

    su.execute(
        "CREATE ROLE " + ROLE + " LOGIN PASSWORD 'advisor' NOSUPERUSER NOCREATEDB NOCREATEROLE");
    su.execute("GRANT pg_read_all_stats TO " + ROLE);
  }

  private static ScanReport scanAsAdvisor() {
    try (PgLensEngine engine =
        PgLensEngine.connect(new ConnectionTarget(DB.getJdbcUrl(), ROLE, "advisor", DB_NAME))) {
      return engine.scan(RankBy.TOTAL_TIME, 20, 1);
    }
  }

  @Test
  void aRoleWithoutAccessIsToldWhichGrantsFixIt_thenGetsRunnableSqlForEveryShape()
      throws Exception {
    // 1. The README's minimum (pg_read_all_stats only): no plan can be captured, and the report
    //    says why and how to fix it — not "can't be generically explained".
    ScanReport denied = scanAsAdvisor();
    List<QueryReport> ours =
        denied.queries().stream().filter(q -> !q.normalizedText().contains("quote_ident")).toList();
    assertThat(ours).isNotEmpty().noneMatch(QueryReport::planCaptured);
    assertThat(ours).allSatisfy(q -> assertThat(q.planError()).contains("permission denied for "));
    assertThat(denied.notes())
        .anySatisfy(
            n ->
                assertThat(n)
                    .contains("GRANT USAGE ON SCHEMA app, billing TO advisor;")
                    .contains("GRANT SELECT ON ALL TABLES IN SCHEMA app, billing, public TO"));

    // 2. Apply exactly the advice, as the owner would.
    su.execute("GRANT USAGE ON SCHEMA app, billing TO " + ROLE);
    su.execute("GRANT SELECT ON ALL TABLES IN SCHEMA app, billing, public TO " + ROLE);

    ScanReport report = scanAsAdvisor();
    assertThat(report.notes()).noneMatch(n -> n.contains("GRANT"));
    List<String> validated =
        report.queries().stream()
            .flatMap(q -> q.recommendations().stream())
            .filter(r -> r.validation().status() == Status.PLANNER_VALIDATED)
            .map(r -> r.candidate().ddl())
            .distinct()
            .toList();

    assertThat(validated)
        .contains(
            "CREATE INDEX idx_useraccounts_email ON app.\"UserAccounts\" (\"Email\");",
            "CREATE INDEX idx_useraccounts_tenant_id ON app.\"UserAccounts\" (tenant_id);",
            "CREATE INDEX idx_post_authorid_createdat ON \"Post\" (\"authorId\", \"createdAt\");",
            "CREATE INDEX idx_order_user ON \"order\" (\"user\");",
            "CREATE INDEX idx_invoices_acct ON billing.invoices (acct);");
    // No partition gets an index of its own: the parent's covers them all.
    assertThat(validated).noneMatch(ddl -> ddl.contains("invoices_20"));

    // 3. The copy-paste promise: each one runs as written (in a transaction rolled back).
    try (Connection c = DriverManagerDataSourceHolder.connection()) {
      c.setAutoCommit(false);
      for (String ddl : validated) {
        try (Statement st = c.createStatement()) {
          st.execute(ddl);
        }
      }
      c.rollback();
    }

    // 4. Nothing hypothetical left behind in the scan's session is checked by the validator's own
    //    reset; the validated partitioned index carries a footprint with the partitions' size.
    Recommendation invoices =
        report.queries().stream()
            .flatMap(q -> q.recommendations().stream())
            .filter(r -> r.candidate().table().equals("billing.invoices"))
            .filter(r -> r.validation().status() == Status.PLANNER_VALIDATED)
            .findFirst()
            .orElseThrow();
    assertThat(invoices.validation().footprint()).isNotNull();
    assertThat(invoices.validation().footprint().tableBytes()).isPositive();
  }

  @Test
  void quotesIdentifiersWheneverPostgresDoes() {
    // Every keyword Postgres quotes, Java quotes identically (Java may quote a few newer keywords
    // an older major doesn't reserve yet — still valid SQL).
    List<Map<String, Object>> rows =
        su.queryForList("SELECT word, quote_ident(word) AS quoted FROM pg_get_keywords()");
    assertThat(rows).isNotEmpty();
    for (Map<String, Object> row : rows) {
      String word = (String) row.get("word");
      String quoted = (String) row.get("quoted");
      if (!quoted.equals(word)) {
        assertThat(SqlIdent.quote(word)).as(word).isEqualTo(quoted);
      }
    }
    for (String name :
        List.of("orders", "UserAccounts", "a$b", "1st", "Émile", "has space", "q\"uote", "_x9")) {
      assertThat(SqlIdent.quote(name))
          .as(name)
          .isEqualTo(su.queryForObject("SELECT quote_ident(?)", String.class, name));
    }
  }

  /** A plain superuser connection for the rolled-back DDL check. */
  private static final class DriverManagerDataSourceHolder {
    static Connection connection() throws java.sql.SQLException {
      return new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
          .getConnection();
    }
  }
}
