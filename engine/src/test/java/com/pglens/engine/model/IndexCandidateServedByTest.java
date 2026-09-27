package com.pglens.engine.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IndexCandidateServedByTest {

  private static final IndexCandidate ORDERS_CUSTOMER =
      IndexCandidate.parseDdl("CREATE INDEX idx_orders_customer_id ON orders (customer_id);")
          .orElseThrow();

  @Test
  void anIndexOnTheSameKeyServesItWhateverItsName() {
    assertThat(ORDERS_CUSTOMER.servedBy(index("orders", "my_own_name", "btree", "customer_id")))
        .isTrue();
  }

  @Test
  void aWiderIndexStartingWithTheKeyServesIt() {
    assertThat(
            ORDERS_CUSTOMER.servedBy(
                index("orders", "orders_customer_created", "btree", "customer_id", "created_at")))
        .isTrue();
  }

  @Test
  void aDifferentLeadingColumnTableMethodOrAPartialIndexDoesNot() {
    assertThat(ORDERS_CUSTOMER.servedBy(index("orders", "x", "btree", "created_at", "customer_id")))
        .isFalse();
    assertThat(ORDERS_CUSTOMER.servedBy(index("invoices", "x", "btree", "customer_id"))).isFalse();
    assertThat(ORDERS_CUSTOMER.servedBy(index("orders", "x", "hash", "customer_id"))).isFalse();
    assertThat(
            ORDERS_CUSTOMER.servedBy(
                new IndexInfo(
                    "orders", "x", List.of("customer_id"), false, false, "btree", "status = 1")))
        .isFalse();
    assertThat(ORDERS_CUSTOMER.servedBy(null)).isFalse();
  }

  @Test
  void quotedNamesAndOtherSchemasMatchTheirIdentity() {
    IndexCandidate post =
        IndexCandidate.parseDdl("CREATE INDEX idx_post_authorid ON app.\"Post\" (\"authorId\");")
            .orElseThrow();
    assertThat(post.servedBy(index("app.\"Post\"", "Post_authorId_idx", "btree", "authorId")))
        .isTrue();
    IndexCandidate gin =
        IndexCandidate.parseDdl("CREATE INDEX idx_events_payload ON events USING gin (payload);")
            .orElseThrow();
    assertThat(gin.servedBy(index("events", "events_payload_gin", "gin", "payload"))).isTrue();
  }

  private static IndexInfo index(String table, String name, String method, String... columns) {
    return new IndexInfo(table, name, List.of(columns), false, false, method, null);
  }
}
