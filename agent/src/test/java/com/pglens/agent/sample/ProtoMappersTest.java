package com.pglens.agent.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.IndexInfo;
import com.pglens.engine.model.StatementStat;
import com.pglens.engine.model.TableInfo;
import com.pglens.engine.model.ValidationResult;
import com.pglens.engine.model.ValidationResult.Status;
import com.pglens.proto.v1.IndexStat;
import com.pglens.proto.v1.QueryStatSample;
import com.pglens.proto.v1.QueryText;
import com.pglens.proto.v1.TableStat;
import com.pglens.proto.v1.ValidateResult;
import com.pglens.proto.v1.ValidationStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure tests for the wire mappers — no DB, no gRPC. They pin exactly what leaves the agent: raw
 * cumulative counters (never a derived/lifetime mean), and an honest plan-captured flag.
 */
class ProtoMappersTest {

  private static StatementStat stat(long queryId, boolean truncated) {
    return new StatementStat(
        queryId,
        "select * from orders where status = $1",
        truncated,
        /* calls= */ 150,
        /* totalExecTimeMs= */ 800.5,
        /* meanExecTimeMs= */ 5.337,
        /* rows= */ 1600,
        /* sharedBlksHit= */ 4200,
        /* sharedBlksRead= */ 90);
  }

  @Test
  void toSampleCarriesRawCumulativeCountersAndNotTheMean() {
    QueryStatSample sample = ProtoMappers.toSample(stat(42, false), "hash-42");

    assertThat(sample.getQueryid()).isEqualTo(42);
    assertThat(sample.getTextHash()).isEqualTo("hash-42");
    assertThat(sample.getCalls()).isEqualTo(150);
    assertThat(sample.getTotalExecTimeMs()).isCloseTo(800.5, within(1e-9));
    assertThat(sample.getRows()).isEqualTo(1600);
    assertThat(sample.getSharedBlksHit()).isEqualTo(4200);
    assertThat(sample.getSharedBlksRead()).isEqualTo(90);
    // There is no mean field on the wire: the server derives it from deltas (charter #1). This is a
    // compile-time guarantee, asserted here as documentation of intent.
    assertThat(QueryStatSample.getDescriptor().findFieldByName("mean_exec_time_ms")).isNull();
  }

  @Test
  void toQueryTextWithACapturedPlanFlagsItTrue() {
    QueryText text =
        ProtoMappers.toQueryText(stat(7, false), "hash-7", Optional.of("{\"Plan\":{}}"));

    assertThat(text.getQueryid()).isEqualTo(7);
    assertThat(text.getTextHash()).isEqualTo("hash-7");
    assertThat(text.getNormalizedText()).isEqualTo("select * from orders where status = $1");
    assertThat(text.getPlanCaptured()).isTrue();
    assertThat(text.getPlanJson()).isEqualTo("{\"Plan\":{}}");
    assertThat(text.getTruncated()).isFalse();
  }

  @Test
  void toQueryTextWithoutAPlanIsHonestlyEmptyNotFabricated() {
    QueryText text = ProtoMappers.toQueryText(stat(9, true), "hash-9", Optional.empty());

    assertThat(text.getPlanCaptured()).isFalse();
    assertThat(text.getPlanJson()).isEmpty();
    assertThat(text.getTruncated()).as("truncation flag is a property of the text").isTrue();
  }

  @Test
  void toProtoCatalogCarriesEstimatesDetectionInputsAndHygieneFields() {
    IndexInfo emailIx =
        new IndexInfo(
            "customers",
            "customers_email_idx",
            List.of("email"),
            /* unique= */ true,
            /* primary= */ false,
            "btree",
            /* predicate= */ null,
            "CREATE UNIQUE INDEX customers_email_idx ON customers USING btree (email)",
            /* constraintBacked= */ true,
            /* idxScan= */ 4200L);
    TableInfo customers = new TableInfo("customers", 100_000, List.of(emailIx));
    CatalogSnapshot engineCatalog = new CatalogSnapshot(Map.of("customers", customers));

    com.pglens.proto.v1.CatalogSnapshot proto = ProtoMappers.toProtoCatalog(engineCatalog);

    assertThat(proto.getTablesList()).hasSize(1);
    TableStat table = proto.getTables(0);
    assertThat(table.getTableName()).isEqualTo("customers");
    assertThat(table.getEstRows()).isEqualTo(100_000);

    assertThat(proto.getIndexesList()).hasSize(1);
    IndexStat index = proto.getIndexes(0);
    assertThat(index.getIndexName()).isEqualTo("customers_email_idx");
    assertThat(index.getTableName()).isEqualTo("customers");
    assertThat(index.getColumnsList()).containsExactly("email"); // the detection input
    assertThat(index.getMethod()).isEqualTo("btree");
    assertThat(index.getIsUnique()).isTrue();
    assertThat(index.getIsPrimary()).isFalse();
    // Hygiene fields (Step 6) now travel on the wire.
    assertThat(index.getDefinition()).contains("customers_email_idx");
    assertThat(index.getConstraintBacked()).isTrue();
    assertThat(index.getIdxScan()).isEqualTo(4200L);
  }

  @Test
  void toValidateResultCarriesCostsForAValidatedVerdict() {
    ValidationResult vr =
        new ValidationResult(Status.PLANNER_VALIDATED, 1000.0, 400.0, 0.6, true, "validated");

    ValidateResult proto = ProtoMappers.toValidateResult(42, vr);

    assertThat(proto.getJobId()).isEqualTo(42);
    assertThat(proto.getStatus()).isEqualTo(ValidationStatus.PLANNER_VALIDATED);
    assertThat(proto.hasBeforeCost()).isTrue();
    assertThat(proto.getBeforeCost()).isCloseTo(1000.0, within(1e-9));
    assertThat(proto.getRelativeDrop()).isCloseTo(0.6, within(1e-9));
    assertThat(proto.getUsed()).isTrue();
  }

  @Test
  void toValidateResultLeavesCostsUnsetForANotValidatedVerdict() {
    // NOT_PLANNER_VALIDATED has null costs — they must be ABSENT on the wire, not a fabricated 0.0.
    ValidationResult vr =
        new ValidationResult(Status.NOT_PLANNER_VALIDATED, null, null, null, false, "GIN");

    ValidateResult proto = ProtoMappers.toValidateResult(7, vr);

    assertThat(proto.getStatus()).isEqualTo(ValidationStatus.NOT_PLANNER_VALIDATED);
    assertThat(proto.hasBeforeCost()).isFalse();
    assertThat(proto.hasAfterCost()).isFalse();
    assertThat(proto.hasRelativeDrop()).isFalse();
    assertThat(proto.getReason()).isEqualTo("GIN");
  }
}
