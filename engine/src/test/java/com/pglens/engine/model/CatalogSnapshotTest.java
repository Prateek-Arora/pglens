package com.pglens.engine.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogSnapshotTest {

  private static final CatalogSnapshot USER_TABLES =
      new CatalogSnapshot(
          Map.of(
              "orders", new TableInfo("orders", 1_000, List.of()),
              "billing.invoices", new TableInfo("billing.invoices", 1_000, List.of()),
              "app.\"Post\"", new TableInfo("app.\"Post\"", 1_000, List.of())));

  @Test
  void userTablesAreIndexable() {
    assertThat(USER_TABLES.indexable("orders")).isTrue();
    assertThat(USER_TABLES.indexable("billing.invoices")).isTrue();
    assertThat(USER_TABLES.indexable("app.\"Post\"")).isTrue();
  }

  @Test
  void systemRelationsNeverAre() {
    for (CatalogSnapshot catalog : List.of(USER_TABLES, CatalogSnapshot.empty())) {
      assertThat(catalog.indexable("pg_catalog.pg_authid")).isFalse();
      assertThat(catalog.indexable("pg_authid")).isFalse();
      assertThat(catalog.indexable("information_schema.columns")).isFalse();
      assertThat(catalog.indexable("pg_toast.pg_toast_2619")).isFalse();
      assertThat(catalog.indexable("pg_temp_3.scratch")).isFalse();
      assertThat(catalog.indexable(null)).isFalse();
    }
  }

  @Test
  void aTableMissingFromAKnownCatalogIsNot() {
    assertThat(USER_TABLES.indexable("customers")).isFalse();
    // With no catalog to check against, a user-schema name is given the benefit of the doubt.
    assertThat(CatalogSnapshot.empty().indexable("customers")).isTrue();
  }

  @Test
  void aUserTableNamedLikeACatalogStaysIndexable() {
    CatalogSnapshot catalog =
        new CatalogSnapshot(Map.of("pg_jobs", new TableInfo("pg_jobs", 10, List.of())));
    assertThat(catalog.indexable("pg_jobs")).isTrue();
  }
}
