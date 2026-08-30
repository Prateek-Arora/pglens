package com.pglens.agent.sample;

import com.pglens.agent.config.PglensAgentProperties;
import com.pglens.agent.grpc.IngestClient;
import com.pglens.engine.db.CatalogReader;
import com.pglens.engine.db.DataSources;
import com.pglens.engine.db.PlanCapturer;
import com.pglens.engine.db.StatsReader;
import com.pglens.engine.model.RankBy;
import com.pglens.engine.model.StatementStat;
import com.pglens.proto.v1.IngestSummary;
import com.pglens.proto.v1.SampleBatch;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled sampler (ADR-0023 {@code a-pull}). Each interval it reads this snapshot of {@code
 * pg_stat_statements} — raw cumulative counters + the global {@code stats_reset} — registers any
 * newly-seen query's text and generic plan <em>once</em>, and client-streams the whole batch to the
 * server, which computes the per-interval deltas (ADR-0024). The agent holds no baselines: it
 * samples, sends, and forgets.
 *
 * <p>Failure is per-cycle and non-fatal. A monitored-DB error resets the single connection and the
 * next interval retries; a send failure leaves the new-text registrations un-marked so they are
 * re-sent next interval (the server upserts them idempotently), and loses no data window because
 * the server's deltas are anchored to the last <em>persisted</em> snapshot, not to this send.
 */
@Component
public class SampleCollector {

  private static final Logger log = LoggerFactory.getLogger(SampleCollector.class);
  private static final long SEND_TIMEOUT_SECONDS = 30;

  private final PglensAgentProperties props;
  private final JdbcTemplate jdbc;
  private final SingleConnectionDataSource monitoredDataSource;
  private final StatsReader statsReader;
  private final CatalogReader catalogReader;
  private final PlanCapturer planCapturer;
  private final IngestClient ingestClient;

  // queryids whose text+plan the server has already accepted; only added after a successful send.
  private final Set<Long> registeredQueryIds = ConcurrentHashMap.newKeySet();

  public SampleCollector(
      PglensAgentProperties props,
      JdbcTemplate monitoredJdbcTemplate,
      SingleConnectionDataSource monitoredDataSource,
      StatsReader statsReader,
      CatalogReader catalogReader,
      PlanCapturer planCapturer,
      IngestClient ingestClient) {
    this.props = props;
    this.jdbc = monitoredJdbcTemplate;
    this.monitoredDataSource = monitoredDataSource;
    this.statsReader = statsReader;
    this.catalogReader = catalogReader;
    this.planCapturer = planCapturer;
    this.ingestClient = ingestClient;
    if (props.getToken() == null || props.getToken().isBlank()) {
      log.warn(
          "pglens.agent.token is empty — the server will reject every batch with UNAUTHENTICATED "
              + "until a token is configured");
    }
  }

  @Scheduled(fixedDelayString = "${pglens.agent.sample.interval-ms}", initialDelayString = "2000")
  public void sample() {
    long sampledAtMs = System.currentTimeMillis();

    SampleBatch batch;
    List<Long> newlyRegistered;
    try {
      // Re-assert the DB-level read-only + timeout guards each cycle: idempotent, and it restores
      // them after any reconnect so the monitored DB is never writable from the agent.
      DataSources.applySessionGuards(jdbc);

      Optional<Instant> statsReset = statsReader.globalStatsReset();
      List<StatementStat> stats =
          statsReader.topStatements(
              RankBy.TOTAL_TIME, props.getSample().getTopN(), props.getSample().getMinCalls());

      SampleBatch.Builder builder =
          SampleBatch.newBuilder()
              .setDbName(nullToEmpty(props.getDbName()))
              .setDbHost(nullToEmpty(props.getDbHost()))
              .setAgentSampleEpochMs(sampledAtMs)
              .setStatsResetEpochMs(statsReset.map(Instant::toEpochMilli).orElse(0L))
              // The catalog (table estimates + existing indexes) the server persists for detection
              // (ADR-0028). Small and slow-changing; sent every interval, replacing the server's
              // copy.
              .setCatalog(ProtoMappers.toProtoCatalog(catalogReader.read()));

      newlyRegistered = new ArrayList<>();
      for (StatementStat stat : stats) {
        String textHash = TextHash.sha256Hex(stat.query());
        builder.addSamples(ProtoMappers.toSample(stat, textHash));
        if (!registeredQueryIds.contains(stat.queryId())) {
          Optional<String> plan = planCapturer.captureGenericPlanJson(stat.query());
          builder.addNewTexts(ProtoMappers.toQueryText(stat, textHash, plan));
          newlyRegistered.add(stat.queryId());
        }
      }
      batch = builder.build();
    } catch (DataAccessException dbErr) {
      log.warn(
          "monitored-db read failed ({}); resetting the connection, retrying next interval",
          dbErr.getMostSpecificCause().getMessage());
      monitoredDataSource.resetConnection();
      return;
    }

    try {
      IngestSummary summary = ingestClient.send(batch, SEND_TIMEOUT_SECONDS);
      registeredQueryIds.addAll(newlyRegistered);
      log.info(
          "streamed {} samples ({} new texts) for db '{}'; server watermark {}",
          batch.getSamplesCount(),
          batch.getNewTextsCount(),
          props.getDbName(),
          summary.getServerReceiveEpochMs());
    } catch (IngestClient.IngestException sendErr) {
      log.warn(
          "ingest send failed for db '{}' ({}); {} new texts will be re-registered next interval",
          props.getDbName(),
          sendErr.getMessage(),
          newlyRegistered.size());
    }
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
