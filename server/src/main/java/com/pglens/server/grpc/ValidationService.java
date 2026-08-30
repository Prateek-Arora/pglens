package com.pglens.server.grpc;

import com.pglens.proto.v1.LeaseRequest;
import com.pglens.proto.v1.ReportAck;
import com.pglens.proto.v1.ValidateRequest;
import com.pglens.proto.v1.ValidateResult;
import com.pglens.proto.v1.ValidationGrpc;
import com.pglens.server.persistence.MonitoredDb;
import com.pglens.server.persistence.ValidationRepository;
import com.pglens.server.persistence.ValidationRepository.LeasedJob;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The validation service (ADR-0023 {@code a-pull}). The agent <em>leases</em> pending candidate
 * DDLs (server-streaming), runs {@code HypoPGValidator} next to the monitored DB, and reports the
 * verdicts back (client-streaming); the server never dials into the agent. The authenticated db
 * comes from the gRPC {@link io.grpc.Context} (ADR-0027), so an agent can only lease/report for its
 * own db.
 */
@Component
public class ValidationService extends ValidationGrpc.ValidationImplBase {

  private static final Logger log = LoggerFactory.getLogger(ValidationService.class);
  private static final int DEFAULT_LEASE = 10;
  private static final int MAX_LEASE = 100;

  private final ValidationRepository repository;
  private final TransactionTemplate tx;

  public ValidationService(ValidationRepository repository, TransactionTemplate tx) {
    this.repository = repository;
    this.tx = tx;
  }

  @Override
  public void leaseValidations(
      LeaseRequest request, StreamObserver<ValidateRequest> responseObserver) {
    MonitoredDb db = AuthInterceptor.MONITORED_DB.get();
    if (db == null) {
      responseObserver.onError(
          Status.UNAUTHENTICATED.withDescription("no authenticated db in context").asException());
      return;
    }
    int max = clampLease(request.getMax());
    List<LeasedJob> jobs = repository.lease(db.id(), max);
    for (LeasedJob job : jobs) {
      responseObserver.onNext(
          ValidateRequest.newBuilder()
              .setJobId(job.id())
              .setQueryid(job.queryid())
              .setNormalizedSql(job.normalizedSql())
              .setCandidateDdl(job.candidateDdl())
              .setAccessMethod(job.accessMethod())
              .build());
    }
    responseObserver.onCompleted();
  }

  @Override
  public StreamObserver<ValidateResult> reportValidations(
      StreamObserver<ReportAck> responseObserver) {
    MonitoredDb db = AuthInterceptor.MONITORED_DB.get();
    if (db == null) {
      responseObserver.onError(
          Status.UNAUTHENTICATED.withDescription("no authenticated db in context").asException());
      return NO_OP;
    }
    return new ResultObserver(db, responseObserver);
  }

  private final class ResultObserver implements StreamObserver<ValidateResult> {
    private final MonitoredDb db;
    private final StreamObserver<ReportAck> responseObserver;
    private int accepted;

    ResultObserver(MonitoredDb db, StreamObserver<ReportAck> responseObserver) {
      this.db = db;
      this.responseObserver = responseObserver;
    }

    @Override
    public void onNext(ValidateResult result) {
      // Persist the recommendation and close the job atomically per result.
      Boolean recorded = tx.execute(status -> repository.recordResult(db.id(), result));
      if (Boolean.TRUE.equals(recorded)) {
        accepted++;
      } else {
        log.warn(
            "ignored a validation result for job {} — not a pending/known job of db '{}'",
            result.getJobId(),
            db.name());
      }
    }

    @Override
    public void onError(Throwable t) {
      log.warn("validation report stream from db '{}' failed: {}", db.name(), t.toString());
    }

    @Override
    public void onCompleted() {
      responseObserver.onNext(ReportAck.newBuilder().setAccepted(accepted).build());
      responseObserver.onCompleted();
    }
  }

  private static int clampLease(int requested) {
    if (requested <= 0) {
      return DEFAULT_LEASE;
    }
    return Math.min(requested, MAX_LEASE);
  }

  private static final StreamObserver<ValidateResult> NO_OP =
      new StreamObserver<>() {
        @Override
        public void onNext(ValidateResult value) {}

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {}
      };
}
