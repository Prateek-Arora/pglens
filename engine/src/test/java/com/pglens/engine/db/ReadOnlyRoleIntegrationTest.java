package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.ConnectionTarget;
import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.RankBy;
import com.pglens.engine.model.Recommendation;
import com.pglens.engine.model.StatementStat;
import com.pglens.engine.model.ValidationResult.Status;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The DB-level read-only role {@code pglens_ro} (ADR-0030 — closes the item ADR-0020 deferred to
 * Phase 2), proven against the real monitored image. Two guarantees:
 *
 * <ol>
 *   <li><b>It can do everything PgLens needs</b> as this least-privilege role over the agent's real
 *       guarded connection: read <em>all</em> statements out of {@code pg_stat_statements} with
 *       their text (proving the {@code pg_read_all_stats} grant — without it another role's text is
 *       redacted to {@code <insufficient privilege>}), read the catalog, and run a full HypoPG
 *       validation (a hypothetical index is session-local, so it works even inside the read-only
 *       transaction).
 *   <li><b>It cannot write</b> — and that holds at the database, independent of the session guard:
 *       even on a plain connection with no {@code READ ONLY} set, an {@code INSERT} is rejected
 *       with "permission denied", because the role holds no write privilege. Defense in depth on
 *       top of the session guard the agent already re-asserts each cycle (whose read-only rejection
 *       is proven separately in {@code PgLensEngineIntegrationTest}).
 * </ol>
 */
@Tag("it")
@Testcontainers
class ReadOnlyRoleIntegrationTest {

  @Container static final PostgreSQLContainer<?> DB = MonitoredDbContainer.create();

  private static final String DB_NAME = "pglens_demo";

  /** The agent's real path: the read-only role, with the session guards applied. */
  private static JdbcTemplate roGuarded;

  @BeforeAll
  static void seedAndCreateRole() {
    MonitoredDbContainer.initSchema(DB);

    // Seed + warm as the bootstrap superuser (the only writable path). customer_id is high
    // cardinality so an index on it is a real, planner-validated win.
    ConnectionTarget superuser =
        new ConnectionTarget(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), DB_NAME);
    JdbcTemplate su = new JdbcTemplate(DataSources.forScan(superuser));
    su.execute(
        "INSERT INTO customers (full_name, email, country, segment) "
            + "SELECT 'c' || g, 'c' || g || '@example.com', 'US', 'standard' "
            + "FROM generate_series(1, 1000) g");
    su.execute(
        "INSERT INTO orders (customer_id, status, total_cents, created_at) "
            + "SELECT (random() * 999 + 1)::int, 'completed', (random() * 10000)::int, "
            + "now() - (g || ' minutes')::interval "
            + "FROM generate_series(1, 20000) g");
    su.execute("ANALYZE customers");
    su.execute("ANALYZE orders");
    // A distinctive statement so pg_stat_statements holds one whose text pglens_ro must be able to
    // read. Run by the superuser — so seeing its text at all proves pg_read_all_stats.
    su.queryForObject("SELECT count(*) FROM orders WHERE customer_id = 42", Long.class);

    // Create the read-only role AFTER the schema exists, then open the agent's guarded connection.
    MonitoredDbContainer.initReadOnlyRole(DB);
    ConnectionTarget ro = new ConnectionTarget(DB.getJdbcUrl(), "pglens_ro", "pglens_ro", DB_NAME);
    roGuarded = new JdbcTemplate(DataSources.forScan(ro));
    DataSources.applySessionGuards(roGuarded);
  }

  @Test
  void readOnlyRoleReadsAllStatsCatalogAndValidatesWithHypopg() {
    // pg_read_all_stats: pglens_ro sees the superuser's statement text, not "<insufficient
    // privilege>". The WHERE clause of the warmed SELECT is the fingerprint (the seeding INSERT
    // mentions customer_id as a column, but only the SELECT has "WHERE customer_id").
    List<StatementStat> stats = new StatsReader(roGuarded).topStatements(RankBy.TOTAL_TIME, 50, 1);
    assertThat(stats).isNotEmpty();
    assertThat(stats)
        .as("pglens_ro must see full statement text via pg_read_all_stats")
        .anyMatch(s -> s.query().toLowerCase().contains("where customer_id"));

    // Catalog read works as the read-only role.
    CatalogSnapshot catalog = new CatalogReader(roGuarded).read();
    assertThat(catalog.table("orders")).isPresent();

    // The load-bearing claim: a full HypoPG validation runs as pglens_ro inside the read-only
    // transaction and leaves nothing behind.
    Recommendation rec =
        new HypoPGValidator(roGuarded)
            .validate(
                "SELECT * FROM orders WHERE customer_id = $1",
                List.of(
                    IndexCandidate.of(
                        "orders",
                        List.of("customer_id"),
                        AccessMethod.BTREE,
                        List.of("R1"),
                        "test candidate")))
            .get(0);
    assertThat(rec.validation().status()).isEqualTo(Status.PLANNER_VALIDATED);
    assertThat(rec.validation().indexUsed()).isTrue();
    Integer live = roGuarded.queryForObject("SELECT count(*) FROM hypopg()", Integer.class);
    assertThat(live).as("no hypothetical index left behind").isZero();
  }

  @Test
  void readOnlyRoleHasNoWritePrivilegeEvenWithoutTheSessionGuard() {
    // A plain pglens_ro connection with NO read-only guard applied — isolating the role's own
    // privilege. The write is still rejected, because the role was never granted INSERT.
    ConnectionTarget ro = new ConnectionTarget(DB.getJdbcUrl(), "pglens_ro", "pglens_ro", DB_NAME);
    JdbcTemplate rawRo = new JdbcTemplate(DataSources.forScan(ro));

    // Spring wraps the driver's PSQLException, so the "permission denied for table orders" text is
    // on the root cause; assert against the stack trace so the level of wrapping doesn't matter.
    assertThatThrownBy(
            () ->
                rawRo.execute(
                    "INSERT INTO orders (customer_id, status, total_cents, created_at) "
                        + "VALUES (1, 'completed', 100, now())"))
        .hasStackTraceContaining("permission denied");
  }
}
