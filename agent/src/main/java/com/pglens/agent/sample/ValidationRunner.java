package com.pglens.agent.sample;

import com.pglens.agent.config.PglensAgentProperties;
import com.pglens.agent.grpc.ValidationClient;
import com.pglens.engine.db.DataSources;
import com.pglens.engine.db.HypoPGValidator;
import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.ValidationResult;
import com.pglens.proto.v1.ReportAck;
import com.pglens.proto.v1.ValidateRequest;
import com.pglens.proto.v1.ValidateResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The edge-validation loop (ADR-0023 {@code a-pull}). Each cycle it leases pending candidate DDLs
 * from the server, runs {@link HypoPGValidator} next to the monitored DB (create a hypothetical
 * index, re-EXPLAIN GENERIC_PLAN, keep only a real planner-used cost drop — nothing is built on the
 * real DB), and reports the verdicts back. Runs on the same single scheduler thread as the sampler,
 * so the two never touch the one monitored connection concurrently.
 */
@Component
public class ValidationRunner {

  private static final Logger log = LoggerFactory.getLogger(ValidationRunner.class);
  private static final long REPORT_TIMEOUT_SECONDS = 30;

  private final PglensAgentProperties props;
  private final JdbcTemplate jdbc;
  private final HypoPGValidator validator;
  private final ValidationClient validationClient;

  public ValidationRunner(
      PglensAgentProperties props,
      JdbcTemplate monitoredJdbcTemplate,
      HypoPGValidator validator,
      ValidationClient validationClient) {
    this.props = props;
    this.jdbc = monitoredJdbcTemplate;
    this.validator = validator;
    this.validationClient = validationClient;
  }

  @Scheduled(
      fixedDelayString = "${pglens.agent.validation.interval-ms}",
      initialDelayString = "5000")
  public void validatePending() {
    List<ValidateRequest> jobs;
    try {
      jobs = validationClient.lease(props.getDbName(), props.getValidation().getMaxLease());
    } catch (RuntimeException leaseFailed) {
      log.warn("validation lease failed: {}", leaseFailed.toString());
      return;
    }
    if (jobs.isEmpty()) {
      return;
    }

    // Same read-only + timeout guards as the sampler (idempotent). HypoPG's hypothetical indexes
    // work under read-only and are reset after every candidate — nothing is created on the real DB.
    try {
      DataSources.applySessionGuards(jdbc);
    } catch (DataAccessException dbErr) {
      log.warn(
          "monitored-db unavailable for validation: {}", dbErr.getMostSpecificCause().getMessage());
      return;
    }

    List<ValidateResult> results = new ArrayList<>(jobs.size());
    for (ValidateRequest job : jobs) {
      try {
        ValidationResult verdict =
            validator.validateDdl(
                job.getNormalizedSql(), job.getCandidateDdl(), accessMethod(job.getAccessMethod()));
        results.add(ProtoMappers.toValidateResult(job.getJobId(), verdict));
      } catch (RuntimeException validationFailed) {
        // Leave this job LEASED and press on with the rest. A crash/throw here leaves the job stuck
        // LEASED — lease-reclaim + dead-letter is deferred to backlog B12 (ADR-0034); the blast
        // radius is bounded to re-validating this one candidate, so it doesn't block the loop.
        log.warn("validating job {} failed: {}", job.getJobId(), validationFailed.toString());
      }
    }
    if (results.isEmpty()) {
      return;
    }

    try {
      ReportAck ack = validationClient.report(results, REPORT_TIMEOUT_SECONDS);
      log.info(
          "reported {} validation result(s) for db '{}'; server accepted {}",
          results.size(),
          props.getDbName(),
          ack.getAccepted());
    } catch (RuntimeException reportFailed) {
      log.warn(
          "validation report failed for db '{}': {}", props.getDbName(), reportFailed.toString());
    }
  }

  private static AccessMethod accessMethod(String name) {
    try {
      return AccessMethod.valueOf(name.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException unknown) {
      return AccessMethod
          .BTREE; // the server enqueues engine AccessMethod names; default defensively
    }
  }
}
