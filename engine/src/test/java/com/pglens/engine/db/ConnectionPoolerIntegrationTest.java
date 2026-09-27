package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pglens.engine.model.ConnectionTarget;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PgLens behind a connection pooler in transaction mode (ADR-0053) — what a pooled Supabase or Neon
 * URL gives it. Before, the read-only guard was a session {@code SET}: through PgBouncer it stayed
 * on a shared server connection and made the next client's (the application's) transactions
 * read-only. Now the guards are startup options, which the pooler refuses, and PgLens stops with a
 * message that says to connect directly — having changed nothing on the server.
 */
@Tag("it")
@Testcontainers
class ConnectionPoolerIntegrationTest {

  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer DB =
      MonitoredDbContainer.create().withNetwork(NETWORK).withNetworkAliases("db");

  @Container
  static final GenericContainer<?> BOUNCER =
      new GenericContainer<>("edoburu/pgbouncer:v1.24.1-p1")
          .withNetwork(NETWORK)
          .dependsOn(DB)
          .withEnv(
              "DATABASE_URL",
              "postgres://" + DB.getUsername() + ":" + DB.getPassword() + "@db:5432/pglens_demo")
          .withEnv("POOL_MODE", "transaction")
          .withEnv("AUTH_TYPE", "scram-sha-256")
          .withExposedPorts(5432)
          .waitingFor(Wait.forLogMessage(".*process up.*", 1));

  @Test
  void aDirectConnectionIsGuarded() {
    JdbcTemplate jdbc = new JdbcTemplate(DataSources.guarded(direct()));

    DataSources.verifySessionGuards(jdbc);

    assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
    assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("30s");
    assertThatThrownBy(() -> jdbc.execute("CREATE TABLE pglens_probe (x int)"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("read-only");
  }

  @Test
  void aTransactionModePoolerIsRefusedAndTheAppKeepsWriting() {
    SingleConnectionDataSource pooled = DataSources.guarded(throughBouncer());
    JdbcTemplate jdbc = new JdbcTemplate(pooled);

    assertThatThrownBy(() -> DataSources.verifySessionGuards(jdbc))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("connection pooler in transaction mode")
        .hasMessageContaining("Connect PgLens directly");
    pooled.destroy();

    // The application, on the same pool afterwards, can still write: nothing leaked.
    JdbcTemplate app = new JdbcTemplate(DataSources.forScan(throughBouncer()));
    app.execute("CREATE TABLE app_write_probe (x int)");
    app.execute("DROP TABLE app_write_probe");
  }

  private static ConnectionTarget direct() {
    return new ConnectionTarget(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), "pglens_demo");
  }

  private static ConnectionTarget throughBouncer() {
    String url =
        "jdbc:postgresql://"
            + BOUNCER.getHost()
            + ":"
            + BOUNCER.getMappedPort(5432)
            + "/pglens_demo";
    return new ConnectionTarget(url, DB.getUsername(), DB.getPassword(), "pglens_demo");
  }
}
