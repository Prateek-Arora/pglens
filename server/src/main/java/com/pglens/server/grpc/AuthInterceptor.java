package com.pglens.server.grpc;

import com.pglens.server.persistence.MonitoredDb;
import com.pglens.server.persistence.MonitoredDbRepository;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Per-agent token authentication (ADR-0027). Every intercepted call must present a bearer token in
 * the {@code x-pglens-token} metadata header; the interceptor hashes it, resolves it to a {@link
 * MonitoredDb}, and pins that db into the gRPC {@link Context} for the service to read. Unknown or
 * missing tokens are rejected with {@code UNAUTHENTICATED} before the service method runs — so
 * PgLens never accepts anonymous stat uploads. Health checks are wired without this interceptor.
 */
@Component
public class AuthInterceptor implements ServerInterceptor {

  /** The authenticated monitored database for the current call. */
  public static final Context.Key<MonitoredDb> MONITORED_DB = Context.key("pglens.monitoredDb");

  static final Metadata.Key<String> TOKEN_HEADER =
      Metadata.Key.of("x-pglens-token", Metadata.ASCII_STRING_MARSHALLER);

  private final MonitoredDbRepository repository;

  public AuthInterceptor(MonitoredDbRepository repository) {
    this.repository = repository;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String token = headers.get(TOKEN_HEADER);
    if (token == null || token.isBlank()) {
      return deny(call, "missing agent token");
    }
    Optional<MonitoredDb> db = repository.findByTokenHash(Tokens.sha256Hex(token));
    if (db.isEmpty()) {
      return deny(call, "unknown agent token");
    }
    Context ctx = Context.current().withValue(MONITORED_DB, db.get());
    return Contexts.interceptCall(ctx, call, headers, next);
  }

  private static <ReqT, RespT> ServerCall.Listener<ReqT> deny(
      ServerCall<ReqT, RespT> call, String reason) {
    call.close(Status.UNAUTHENTICATED.withDescription(reason), new Metadata());
    return new ServerCall.Listener<>() {};
  }
}
