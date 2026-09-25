package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.TargetInfo;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure parts of the copy guard and the DDL identifier check (Phase 2.6, ADR-0042). No Docker. */
class CopyGuardTest {

  @Test
  void theScannedDatabaseIsRecognisedByHostPortAndName() {
    TargetInfo scanned = new TargetInfo("DB.internal", 5432, "shop", "16.4", List.of());
    assertThat(CopyTarget.sameAs(scanned, "db.internal", 5432, "shop")).isTrue();
    assertThat(CopyTarget.sameAs(scanned, "db.internal", 5433, "shop")).isFalse();
    assertThat(CopyTarget.sameAs(scanned, "db.internal", 5432, "shop_copy")).isFalse();
    assertThat(CopyTarget.sameAs(scanned, "other", 5432, "shop")).isFalse();
    // A report older than 1.2 has no port: host + name alone decide (the cautious side).
    TargetInfo old = new TargetInfo("db.internal", null, "shop", "16.4", List.of());
    assertThat(CopyTarget.sameAs(old, "db.internal", 6543, "shop")).isTrue();
  }

  @Test
  void onlyPlainIdentifiersReachTheDdl() {
    assertThatCode(() -> CopyMeasurer.requireIdentifiers(btree("public.orders", "customer_id")))
        .doesNotThrowAnyException();
    assertThatCode(() -> CopyMeasurer.requireIdentifiers(btree("\"Order Items\"", "\"Qty\"")))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> CopyMeasurer.requireIdentifiers(btree("orders", "id); DROP TABLE orders; --")))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(() -> CopyMeasurer.requireIdentifiers(btree("orders; DELETE", "id")))
        .isInstanceOf(SQLException.class);
  }

  @Test
  void theMarkerMustComeFromTheDatabaseItself() {
    assertThat(CopyTarget.MARKER_SQL)
        .contains("pg_db_role_setting")
        .contains("s.setrole = 0")
        .contains("pglens.scratch=on");
  }

  @Test
  void timeoutsAndRejectedWritesAreTold() {
    assertThat(CopyMeasurer.failure(new SQLException("x", "57014")).failure())
        .isEqualTo(com.pglens.engine.confirm.StatementTiming.Failure.TIMEOUT);
    assertThat(CopyMeasurer.failure(new SQLException("x", "25006")).failure())
        .isEqualTo(com.pglens.engine.confirm.StatementTiming.Failure.WRITE_REJECTED);
    assertThat(CopyMeasurer.failure(new SQLException("x", "42P01")).sqlState()).isEqualTo("42P01");
  }

  private static IndexCandidate btree(String table, String column) {
    return IndexCandidate.of(table, List.of(column), AccessMethod.BTREE, List.of(), null);
  }
}
