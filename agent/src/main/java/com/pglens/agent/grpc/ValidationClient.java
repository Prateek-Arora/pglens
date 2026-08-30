package com.pglens.agent.grpc;

import com.pglens.proto.v1.LeaseRequest;
import com.pglens.proto.v1.ReportAck;
import com.pglens.proto.v1.ValidateRequest;
import com.pglens.proto.v1.ValidateResult;
import com.pglens.proto.v1.ValidationGrpc;
import io.grpc.Channel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * gRPC client for the {@code Validation} service (ADR-0023 {@code a-pull}): lease pending candidate
 * DDLs (server-streaming, drained with the blocking stub) and report the verdicts back
 * (client-streaming). The per-agent bearer token is attached to both calls.
 */
public class ValidationClient {

  private final Channel channel;
  private final String token;

  public ValidationClient(Channel channel, String token) {
    this.channel = channel;
    this.token = token == null ? "" : token;
  }

  /**
   * Leases up to {@code max} jobs for {@code dbName}; returns the streamed requests (may be empty).
   */
  public List<ValidateRequest> lease(String dbName, int max) {
    ValidationGrpc.ValidationBlockingStub stub =
        ValidationGrpc.newBlockingStub(channel).withInterceptors(tokenHeader());
    Iterator<ValidateRequest> it =
        stub.leaseValidations(LeaseRequest.newBuilder().setDbName(dbName).setMax(max).build());
    List<ValidateRequest> jobs = new ArrayList<>();
    it.forEachRemaining(jobs::add);
    return jobs;
  }

  /**
   * Reports the verdicts back and returns the server's ack. Blocks up to {@code timeoutSeconds}.
   */
  public ReportAck report(List<ValidateResult> results, long timeoutSeconds) {
    ValidationGrpc.ValidationStub stub =
        ValidationGrpc.newStub(channel).withInterceptors(tokenHeader());
    CompletableFuture<ReportAck> ack = new CompletableFuture<>();
    StreamObserver<ValidateResult> request =
        stub.reportValidations(
            new StreamObserver<>() {
              private ReportAck received;

              @Override
              public void onNext(ReportAck value) {
                this.received = value;
              }

              @Override
              public void onError(Throwable t) {
                ack.completeExceptionally(t);
              }

              @Override
              public void onCompleted() {
                ack.complete(received);
              }
            });

    try {
      for (ValidateResult result : results) {
        request.onNext(result);
      }
      request.onCompleted();
      return ack.get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      request.onError(e);
      Thread.currentThread().interrupt();
      throw new IngestClient.IngestException("interrupted while reporting validations", e);
    } catch (ExecutionException e) {
      throw new IngestClient.IngestException("server rejected the validation report", e.getCause());
    } catch (TimeoutException e) {
      request.onError(e);
      throw new IngestClient.IngestException("timed out waiting for the validation ack", e);
    }
  }

  private io.grpc.ClientInterceptor tokenHeader() {
    Metadata headers = new Metadata();
    headers.put(IngestClient.TOKEN_HEADER, token);
    return MetadataUtils.newAttachHeadersInterceptor(headers);
  }
}
