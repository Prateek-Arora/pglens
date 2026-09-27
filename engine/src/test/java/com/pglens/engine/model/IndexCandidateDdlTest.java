package com.pglens.engine.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link IndexCandidate#parseDdl} is the exact inverse of {@link IndexCandidate#ddl()}. */
class IndexCandidateDdlTest {

  @Test
  void roundTripsEveryShapeTheGeneratorRenders() {
    for (IndexCandidate c :
        List.of(
            IndexCandidate.of(
                "orders", List.of("customer_id"), AccessMethod.BTREE, List.of(), null),
            IndexCandidate.of(
                "orders", List.of("status", "created_at"), AccessMethod.BTREE, List.of(), null),
            IndexCandidate.of("events", List.of("payload"), AccessMethod.GIN, List.of(), null),
            IndexCandidate.of("orders", List.of("created_at"), AccessMethod.BRIN, List.of(), null),
            IndexCandidate.of(
                "app.\"UserAccounts\"", List.of("tenant_id"), AccessMethod.BTREE, List.of(), null),
            IndexCandidate.of(
                "\"Post\"", List.of("authorId", "createdAt"), AccessMethod.BTREE, List.of(), null),
            IndexCandidate.of(
                "billing.\"order\"",
                List.of("user", "a,b"),
                AccessMethod.BTREE,
                List.of(),
                null))) {
      IndexCandidate parsed = IndexCandidate.parseDdl(c.ddl()).orElseThrow();
      assertThat(parsed.table()).isEqualTo(c.table());
      assertThat(parsed.columns()).isEqualTo(c.columns());
      assertThat(parsed.accessMethod()).isEqualTo(c.accessMethod());
      assertThat(parsed.ddl()).isEqualTo(c.ddl());
    }
  }

  @Test
  void rendersSchemaQualifiedQuotedSqlThatPostgresAccepts() {
    assertThat(
            IndexCandidate.of(
                    "app.\"UserAccounts\"",
                    List.of("tenant_id"),
                    AccessMethod.BTREE,
                    List.of(),
                    null)
                .ddl())
        .isEqualTo("CREATE INDEX idx_useraccounts_tenant_id ON app.\"UserAccounts\" (tenant_id);");
    assertThat(
            IndexCandidate.of(
                    "\"Post\"", List.of("authorId", "user"), AccessMethod.BTREE, List.of(), null)
                .ddl())
        .isEqualTo("CREATE INDEX idx_post_authorid_user ON \"Post\" (\"authorId\", \"user\");");
  }

  @Test
  void keepsGeneratedIndexNamesWithinPostgresLimitWithoutCollisions() {
    String longTable = "a_really_long_table_name_that_keeps_on_going_forever";
    String first =
        IndexCandidate.of(
                longTable, List.of("first_long_column", "x"), AccessMethod.BTREE, List.of(), null)
            .suggestedName();
    String second =
        IndexCandidate.of(
                longTable, List.of("first_long_column", "y"), AccessMethod.BTREE, List.of(), null)
            .suggestedName();
    assertThat(first).hasSizeLessThanOrEqualTo(63).matches("[a-z0-9_]+");
    assertThat(second).hasSizeLessThanOrEqualTo(63).isNotEqualTo(first);
  }

  @Test
  void readsOlderBareDdlAsPostgresWould() {
    // Rows written before ADR-0049 hold bare names; Postgres folds them to lower case.
    IndexCandidate old =
        IndexCandidate.parseDdl("CREATE INDEX idx_orders_customer_id ON orders (customer_id);")
            .orElseThrow();
    assertThat(old.table()).isEqualTo("orders");
    assertThat(old.columns()).containsExactly("customer_id");
    assertThat(
            IndexCandidate.parseDdl("CREATE INDEX i ON public.orders (a);").orElseThrow().table())
        .isEqualTo("orders");
  }

  @Test
  void rejectsAnythingElseRatherThanGuessing() {
    assertThat(IndexCandidate.parseDdl(null)).isEmpty();
    assertThat(IndexCandidate.parseDdl("DROP TABLE orders;")).isEmpty();
    assertThat(IndexCandidate.parseDdl("CREATE INDEX i ON t USING rtree (a);")).isEmpty();
    assertThat(IndexCandidate.parseDdl("CREATE INDEX i ON t (a, );")).isEmpty();
    assertThat(IndexCandidate.parseDdl("CREATE INDEX i ON t (lower(a));")).isEmpty();
  }
}
