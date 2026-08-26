package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.ConnectionTarget;
import com.pglens.engine.model.TableInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link CatalogReader} against the real monitored image. It reads the demo
 * schema (no data needed — index structure is deterministic from the DDL alone) and checks that the
 * primary keys are seen and the deliberately-unindexed anti-pattern columns are reported as
 * unindexed.
 */
@Tag("it")
@Testcontainers
class CatalogReaderIntegrationTest {

  @Container static final PostgreSQLContainer<?> DB = MonitoredDbContainer.create();

  private static CatalogSnapshot catalog;

  @BeforeAll
  static void readCatalog() {
    MonitoredDbContainer.initSchema(DB);
    ConnectionTarget target =
        new ConnectionTarget(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), "pglens_demo");
    catalog = new CatalogReader(new JdbcTemplate(DataSources.forScan(target))).read();
  }

  @Test
  void seesEveryDemoTable() {
    assertThat(catalog.tables().keySet())
        .contains("customers", "products", "orders", "order_items", "events");
  }

  @Test
  void reportsPrimaryKeyIndexes() {
    assertThat(catalog.hasIndexLeadingWith("orders", "id")).isTrue();
    TableInfo orders = catalog.table("orders").orElseThrow();
    assertThat(orders.indexes())
        .anySatisfy(ix -> assertThat(ix.primary()).isTrue())
        .allSatisfy(ix -> assertThat(ix.method()).isEqualTo("btree"));
  }

  @Test
  void reportsTheDeliberatelyUnindexedColumnsAsUnindexed() {
    assertThat(catalog.hasIndexLeadingWith("orders", "customer_id")).isFalse();
    assertThat(catalog.hasIndexLeadingWith("order_items", "order_id")).isFalse();
    assertThat(catalog.hasIndexLeadingWith("customers", "email")).isFalse();
    assertThat(catalog.hasIndexLeadingWith("events", "event_type")).isFalse();
  }
}
