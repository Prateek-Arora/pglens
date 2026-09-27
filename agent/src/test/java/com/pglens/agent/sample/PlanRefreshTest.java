package com.pglens.agent.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.IndexInfo;
import com.pglens.engine.model.TableInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanRefreshTest {

  private static final long HOUR = 3_600_000;

  @Test
  void aPlanIsDueUntilCapturedAndAgainAfterTheRefreshPeriod() {
    PlanRefresh refresh = new PlanRefresh(HOUR);
    refresh.observe(catalog());

    assertThat(refresh.due(1L, 0)).isTrue();
    refresh.captured(1L, 0);
    assertThat(refresh.due(1L, HOUR - 1)).isFalse();
    assertThat(refresh.due(1L, HOUR)).isTrue();
  }

  @Test
  void aNewIndexMakesEveryPlanDueUntilItIsCapturedAgain() {
    PlanRefresh refresh = new PlanRefresh(HOUR);
    assertThat(refresh.observe(catalog())).as("first cycle").isFalse();
    refresh.captured(1L, 0);
    refresh.captured(2L, 0);

    assertThat(refresh.observe(catalog())).as("same indexes").isFalse();
    assertThat(refresh.due(1L, 1)).isFalse();

    CatalogSnapshot withIndex = catalog(btree("idx_orders_customer_id", "customer_id"));
    assertThat(refresh.observe(withIndex)).isTrue();
    assertThat(refresh.due(1L, 1)).isTrue();
    assertThat(refresh.due(2L, 1)).isTrue();

    // A failed send leaves them due; once the server has them, they wait again.
    assertThat(refresh.observe(withIndex)).isFalse();
    assertThat(refresh.due(1L, 2)).isTrue();
    refresh.captured(1L, 2);
    assertThat(refresh.due(1L, 3)).isFalse();
    assertThat(refresh.due(2L, 3)).isTrue();
  }

  @Test
  void aDroppedIndexIsAChangeToo() {
    PlanRefresh refresh = new PlanRefresh(HOUR);
    refresh.observe(catalog(btree("idx_orders_customer_id", "customer_id")));
    refresh.captured(1L, 0);

    assertThat(refresh.observe(catalog())).isTrue();
    assertThat(refresh.due(1L, 1)).isTrue();
  }

  private static CatalogSnapshot catalog(IndexInfo... indexes) {
    return new CatalogSnapshot(
        Map.of("orders", new TableInfo("orders", 100_000, List.of(indexes))));
  }

  private static IndexInfo btree(String name, String column) {
    return new IndexInfo("orders", name, List.of(column), false, false, "btree", null);
  }
}
