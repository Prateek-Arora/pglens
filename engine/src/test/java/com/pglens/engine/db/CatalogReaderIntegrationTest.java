package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.ConnectionTarget;
import com.pglens.engine.model.IndexInfo;
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
  private static ConnectionTarget target;

  @BeforeAll
  static void readCatalog() {
    MonitoredDbContainer.initSchema(DB);
    target =
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

  @Test
  void populatesTheHygieneFieldsForAPrimaryKeyIndex() {
    TableInfo orders = catalog.table("orders").orElseThrow();
    IndexInfo pk = orders.indexes().stream().filter(IndexInfo::primary).findFirst().orElseThrow();

    // definition is the real pg_get_indexdef DDL; the PK backs a constraint (guarded); idx_scan is
    // a
    // real cumulative count (>= 0, never fabricated).
    assertThat(pk.definition()).contains(pk.name()).containsIgnoringCase("orders");
    assertThat(pk.constraintBacked()).isTrue();
    assertThat(pk.guarded()).isTrue();
    assertThat(pk.idxScan()).isGreaterThanOrEqualTo(0L);
  }

  @Test
  void marksAnIndexCoveringAForeignKeyColumnAsConstraintBackedSoHygieneNeverDropsIt() {
    // orders.customer_id REFERENCES customers(id) but has no index by default. Add one, and the
    // reader must mark it constraint_backed (it speeds FK enforcement) even though it is not
    // unique.
    JdbcTemplate w = new JdbcTemplate(DataSources.forScan(target));
    w.execute("CREATE INDEX tmp_orders_customer_id ON orders (customer_id)");
    try {
      CatalogSnapshot fresh = new CatalogReader(w).read();
      IndexInfo fkIndex =
          fresh.table("orders").orElseThrow().indexes().stream()
              .filter(ix -> ix.name().equals("tmp_orders_customer_id"))
              .findFirst()
              .orElseThrow();

      assertThat(fkIndex.unique()).isFalse();
      assertThat(fkIndex.primary()).isFalse();
      assertThat(fkIndex.constraintBacked())
          .as("covers the FK orders.customer_id -> customers.id")
          .isTrue();
      assertThat(fkIndex.guarded()).isTrue();
    } finally {
      w.execute("DROP INDEX tmp_orders_customer_id");
    }
  }
}
