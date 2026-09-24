package com.pglens.engine.rank;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.rank.CoverageChecks.Check;
import com.pglens.engine.rank.CoverageChecks.Verdict;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pure tests for the missing "does the general index serve this query too?" checks. */
class CoverageChecksTest {

  private static final String NARROW =
      "CREATE INDEX idx_orders_customer_id ON orders (customer_id);";
  private static final String WIDE =
      "CREATE INDEX idx_orders_customer_id_created_at ON orders (customer_id, created_at);";
  private static final String OTHER = "CREATE INDEX idx_orders_status ON orders (status);";
  private static final String GIN =
      "CREATE INDEX idx_orders_customer_id ON orders USING gin (customer_id);";

  @Test
  void asksForTheWiderIndexAgainstEachQueryOfTheNarrowerOne() {
    Map<String, Set<Long>> validated = new LinkedHashMap<>();
    validated.put(NARROW, Set.of(1L));
    validated.put(WIDE, Set.of(2L));
    validated.put(OTHER, Set.of(3L));

    assertThat(CoverageChecks.missing(validated, Set.of()))
        .containsExactly(new Check(1L, WIDE, "BTREE"));
  }

  @Test
  void skipsPairsThatAlreadyHaveAVerdictAndNonCoveringPairs() {
    Map<String, Set<Long>> validated = new LinkedHashMap<>();
    validated.put(NARROW, Set.of(1L));
    validated.put(WIDE, Set.of(2L));
    validated.put(GIN, Set.of(4L));

    assertThat(CoverageChecks.missing(validated, Set.of(new Verdict(1L, WIDE)))).isEmpty();
  }
}
