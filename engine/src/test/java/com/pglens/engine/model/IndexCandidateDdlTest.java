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
            IndexCandidate.of(
                "orders", List.of("created_at"), AccessMethod.BRIN, List.of(), null))) {
      IndexCandidate parsed = IndexCandidate.parseDdl(c.ddl()).orElseThrow();
      assertThat(parsed.table()).isEqualTo(c.table());
      assertThat(parsed.columns()).isEqualTo(c.columns());
      assertThat(parsed.accessMethod()).isEqualTo(c.accessMethod());
      assertThat(parsed.ddl()).isEqualTo(c.ddl());
    }
  }

  @Test
  void rejectsAnythingElseRatherThanGuessing() {
    assertThat(IndexCandidate.parseDdl(null)).isEmpty();
    assertThat(IndexCandidate.parseDdl("DROP TABLE orders;")).isEmpty();
    assertThat(IndexCandidate.parseDdl("CREATE INDEX i ON t USING rtree (a);")).isEmpty();
    assertThat(IndexCandidate.parseDdl("CREATE INDEX i ON t (a, );")).isEmpty();
  }
}
