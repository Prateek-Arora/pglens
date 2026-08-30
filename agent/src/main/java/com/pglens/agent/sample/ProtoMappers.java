package com.pglens.agent.sample;

import com.pglens.engine.model.IndexInfo;
import com.pglens.engine.model.StatementStat;
import com.pglens.engine.model.TableInfo;
import com.pglens.engine.model.ValidationResult;
import com.pglens.proto.v1.IndexStat;
import com.pglens.proto.v1.QueryStatSample;
import com.pglens.proto.v1.QueryText;
import com.pglens.proto.v1.TableStat;
import com.pglens.proto.v1.ValidateResult;
import com.pglens.proto.v1.ValidationStatus;
import java.util.Optional;

/**
 * Pure mappers from engine model types to the wire ({@code .proto}) messages. No I/O, no Spring —
 * so the exact fields the agent puts on the wire are unit-testable without a database or a server.
 * The inverse mapping lives on the server; both sides mirror the same field names to keep the
 * contract from drifting.
 */
public final class ProtoMappers {

  private ProtoMappers() {}

  /**
   * One {@link StatementStat} → a {@link QueryStatSample} of <em>raw cumulative</em> counters. The
   * lifetime {@code meanExecTimeMs} is deliberately dropped: the server derives the per-interval
   * mean from the deltas (a sampled lifetime mean would be a fabricated interval number — charter
   * #1).
   */
  public static QueryStatSample toSample(StatementStat stat, String textHash) {
    return QueryStatSample.newBuilder()
        .setQueryid(stat.queryId())
        .setTextHash(textHash)
        .setCalls(stat.calls())
        .setTotalExecTimeMs(stat.totalExecTimeMs())
        .setRows(stat.rows())
        .setSharedBlksHit(stat.sharedBlksHit())
        .setSharedBlksRead(stat.sharedBlksRead())
        .build();
  }

  /**
   * A one-time registration of a query's normalized text and its captured generic plan. An absent
   * plan (the shape is not generic-plannable) is honestly flagged {@code plan_captured = false}
   * with empty {@code plan_json}, never a fabricated plan.
   */
  public static QueryText toQueryText(
      StatementStat stat, String textHash, Optional<String> planJson) {
    return QueryText.newBuilder()
        .setQueryid(stat.queryId())
        .setTextHash(textHash)
        .setNormalizedText(stat.query())
        .setPlanJson(planJson.orElse(""))
        .setPlanCaptured(planJson.isPresent())
        .setTruncated(stat.truncated())
        .build();
  }

  /**
   * Engine {@link com.pglens.engine.model.CatalogSnapshot} → the wire {@link
   * com.pglens.proto.v1.CatalogSnapshot} the server persists for detection (ADR-0028) and hygiene
   * (ADR-0029). Carries table row-estimates + each index's identity, unique/primary flags, the
   * detection inputs (ordered columns, method, partial predicate), and the hygiene fields:
   * definition, the collapsed constraint_backed guard (PK/UNIQUE/EXCLUSION or FK-covering), and the
   * cumulative idx_scan the server deltas over the window.
   */
  public static com.pglens.proto.v1.CatalogSnapshot toProtoCatalog(
      com.pglens.engine.model.CatalogSnapshot catalog) {
    com.pglens.proto.v1.CatalogSnapshot.Builder builder =
        com.pglens.proto.v1.CatalogSnapshot.newBuilder();
    for (TableInfo table : catalog.tables().values()) {
      builder.addTables(
          TableStat.newBuilder().setTableName(table.name()).setEstRows(table.reltuples()).build());
      for (IndexInfo ix : table.indexes()) {
        builder.addIndexes(
            IndexStat.newBuilder()
                .setIndexName(ix.name())
                .setTableName(ix.table())
                .setIsUnique(ix.unique())
                .setIsPrimary(ix.primary())
                .setConstraintBacked(ix.constraintBacked())
                .setDefinition(ix.definition() == null ? "" : ix.definition())
                .setIdxScan(ix.idxScan())
                .setMethod(ix.method() == null ? "" : ix.method())
                .setPredicate(ix.predicate() == null ? "" : ix.predicate())
                .addAllColumns(ix.columns())
                .build());
      }
    }
    return builder.build();
  }

  /**
   * Engine {@link ValidationResult} (edge HypoPG verdict) → the wire {@link ValidateResult} for job
   * {@code jobId}. The three cost fields are set ONLY when present — a NOT_PLANNER_VALIDATED
   * verdict leaves them unset so the server stores NULL, never a fabricated 0.0 (charter #1). The
   * status enum names mirror the engine's exactly, so the mapping can't drift.
   */
  public static ValidateResult toValidateResult(long jobId, ValidationResult vr) {
    ValidateResult.Builder builder =
        ValidateResult.newBuilder()
            .setJobId(jobId)
            .setStatus(ValidationStatus.valueOf(vr.status().name()))
            .setUsed(vr.indexUsed())
            .setReason(vr.label() == null ? "" : vr.label());
    if (vr.costBefore() != null) {
      builder.setBeforeCost(vr.costBefore());
    }
    if (vr.costAfter() != null) {
      builder.setAfterCost(vr.costAfter());
    }
    if (vr.relativeDelta() != null) {
      builder.setRelativeDrop(vr.relativeDelta());
    }
    return builder.build();
  }
}
