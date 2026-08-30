package com.pglens.server.grpc;

import com.pglens.proto.v1.IngestGrpc;
import com.pglens.proto.v1.IngestSummary;
import com.pglens.proto.v1.QueryStatSample;
import com.pglens.proto.v1.QueryText;
import com.pglens.proto.v1.SampleBatch;
import com.pglens.server.ingest.CumulativeCounters;
import com.pglens.server.ingest.DeltaCalculator;
import com.pglens.server.ingest.DeltaCalculator.Delta;
import com.pglens.server.persistence.CatalogRepository;
import com.pglens.server.persistence.MonitoredDb;
import com.pglens.server.persistence.MonitoredDbRepository;
import com.pglens.server.persistence.SnapshotRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ingest service (ADR-0023 {@code a-pull}). The agent client-streams this interval's cumulative
 * {@code pg_stat_statements} samples (+ any new query-text registrations); the server computes
 * per-interval deltas against the last persisted snapshot (ADR-0024) and appends them to the
 * time-series, then returns one {@link IngestSummary} watermark. Each batch is persisted in a
 * single transaction so a delta row and its advanced anchor commit together.
 */
@Component
public class IngestService extends IngestGrpc.IngestImplBase {

  private static final Logger log = LoggerFactory.getLogger(IngestService.class);

  private final SnapshotRepository store;
  private final MonitoredDbRepository monitoredDbs;
  private final CatalogRepository catalogs;
  private final TransactionTemplate tx;
  private final DeltaCalculator deltaCalculator = new DeltaCalculator();

  public IngestService(
      SnapshotRepository store,
      MonitoredDbRepository monitoredDbs,
      CatalogRepository catalogs,
      TransactionTemplate tx) {
    this.store = store;
    this.monitoredDbs = monitoredDbs;
    this.catalogs = catalogs;
    this.tx = tx;
  }

  @Override
  public StreamObserver<SampleBatch> streamSnapshot(
      StreamObserver<IngestSummary> responseObserver) {
    MonitoredDb db = AuthInterceptor.MONITORED_DB.get();
    if (db == null) {
      responseObserver.onError(
          Status.UNAUTHENTICATED.withDescription("no authenticated db in context").asException());
      return NO_OP;
    }
    return new BatchObserver(db, responseObserver);
  }

  private final class BatchObserver implements StreamObserver<SampleBatch> {
    private final MonitoredDb db;
    private final StreamObserver<IngestSummary> responseObserver;
    private int acceptedSamples;
    private int acceptedTexts;
    private Instant lastReceive;

    BatchObserver(MonitoredDb db, StreamObserver<IngestSummary> responseObserver) {
      this.db = db;
      this.responseObserver = responseObserver;
    }

    @Override
    public void onNext(SampleBatch batch) {
      Instant receiveAt = Instant.now();
      lastReceive = receiveAt;
      Instant agentAt = Instant.ofEpochMilli(batch.getAgentSampleEpochMs());
      // One transaction per batch: each delta row, its advanced anchor, and the recorded
      // stats_reset
      // commit atomically, so a partial failure can't leave a stale anchor that double-counts.
      tx.executeWithoutResult(status -> persistBatch(batch, receiveAt, agentAt));
    }

    private void persistBatch(SampleBatch batch, Instant receiveAt, Instant agentAt) {
      boolean globalReset = detectGlobalReset(batch);

      // Replace this db's catalog (table estimates + existing indexes) so the scheduled analysis
      // (ADR-0028) can rebuild the CatalogSnapshot and run the pure detector server-side, and
      // append
      // this snapshot's cumulative idx_scan counters to the time-series for hygiene's "unused over
      // the window" delta (ADR-0029). Optional: absent on batches that carry only samples.
      if (batch.hasCatalog()) {
        catalogs.replaceCatalog(db.id(), batch.getCatalog());
        catalogs.recordIndexScans(db.id(), batch.getCatalog(), receiveAt);
      }

      for (QueryText text : batch.getNewTextsList()) {
        store.upsertQueryText(db.id(), text);
        acceptedTexts++;
      }
      for (QueryStatSample sample : batch.getSamplesList()) {
        CumulativeCounters current = toCounters(sample);
        Optional<CumulativeCounters> previous = store.lastCumulative(db.id(), sample.getQueryid());
        Optional<Delta> delta = deltaCalculator.delta(previous.orElse(null), current, globalReset);
        delta.ifPresent(
            d -> store.insertDelta(db.id(), sample.getQueryid(), receiveAt, agentAt, d));
        store.upsertCumulative(db.id(), sample.getQueryid(), current, receiveAt);
        acceptedSamples++;
      }
    }

    // A global reset happened this interval iff the batch's pg_stat_statements_info.stats_reset is
    // later than the one we last recorded for this db. The very first batch (no recorded value) is
    // not a reset — its samples establish anchors and emit no deltas anyway.
    private boolean detectGlobalReset(SampleBatch batch) {
      if (batch.getStatsResetEpochMs() <= 0) {
        return false;
      }
      Instant batchReset = Instant.ofEpochMilli(batch.getStatsResetEpochMs());
      Optional<Instant> lastReset = monitoredDbs.lastStatsReset(db.id());
      boolean reset = lastReset.isPresent() && batchReset.isAfter(lastReset.get());
      monitoredDbs.updateLastStatsReset(db.id(), batchReset);
      return reset;
    }

    @Override
    public void onError(Throwable t) {
      log.warn("ingest stream from db '{}' failed: {}", db.name(), t.toString());
    }

    @Override
    public void onCompleted() {
      Instant watermark = lastReceive != null ? lastReceive : Instant.now();
      responseObserver.onNext(
          IngestSummary.newBuilder()
              .setServerReceiveEpochMs(watermark.toEpochMilli())
              .setAcceptedSamples(acceptedSamples)
              .setAcceptedTexts(acceptedTexts)
              .build());
      responseObserver.onCompleted();
    }
  }

  private static CumulativeCounters toCounters(QueryStatSample s) {
    return new CumulativeCounters(
        s.getCalls(),
        s.getTotalExecTimeMs(),
        s.getRows(),
        s.getSharedBlksHit(),
        s.getSharedBlksRead());
  }

  private static final StreamObserver<SampleBatch> NO_OP =
      new StreamObserver<>() {
        @Override
        public void onNext(SampleBatch value) {}

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {}
      };
}
