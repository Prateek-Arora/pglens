package com.pglens.server.grpc;

import com.pglens.proto.v1.HealthGrpc;
import com.pglens.proto.v1.HealthRequest;
import com.pglens.proto.v1.HealthStatus;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Unauthenticated liveness probe (unary). Wired without the {@link AuthInterceptor}. */
@Component
public class HealthService extends HealthGrpc.HealthImplBase {

  private final String version;

  public HealthService(@Value("${pglens.version:0.0.2}") String version) {
    this.version = version;
  }

  @Override
  public void check(HealthRequest request, StreamObserver<HealthStatus> responseObserver) {
    responseObserver.onNext(HealthStatus.newBuilder().setServing(true).setVersion(version).build());
    responseObserver.onCompleted();
  }
}
