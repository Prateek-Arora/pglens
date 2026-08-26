package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.ConnectionTarget;
import com.pglens.engine.model.PlanNode;
import com.pglens.engine.parse.PlanParser;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the DB I/O half against the real monitored image (demo schema loaded via the
 * same initdb scripts compose uses; a fresh, empty database — GENERIC_PLAN plans without needing
 * data). Goes through the real {@link DataSources#forScan} path, so it also proves the
 * simple-query-protocol setup that generic-plan capture requires.
 */
@Tag("it")
@Testcontainers
class PlanCapturerIntegrationTest {

  @Container static final PostgreSQLContainer<?> DB = MonitoredDbContainer.create();

  @BeforeAll
  static void loadSchema() {
    MonitoredDbContainer.initSchema(DB);
  }

  private PlanCapturer capturer() {
    ConnectionTarget target =
        new ConnectionTarget(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), "pglens_demo");
    return new PlanCapturer(new JdbcTemplate(DataSources.forScan(target)));
  }

  @Test
  void capturesGenericPlanForParameterizedQueryAndParses() {
    Optional<String> json =
        capturer().captureGenericPlanJson("SELECT * FROM orders WHERE customer_id = $1");

    assertThat(json).isPresent();
    PlanNode root = new PlanParser().parse(json.get());
    assertThat(root.flatten()).anyMatch(n -> "orders".equals(n.relationName()));
  }

  @Test
  void skipsStatementsThatCannotBeGenericPlanned() {
    assertThat(capturer().captureGenericPlanJson("this is not a statement")).isEmpty();
  }
}
