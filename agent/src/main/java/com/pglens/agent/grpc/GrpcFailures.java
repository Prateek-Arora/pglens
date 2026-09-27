package com.pglens.agent.grpc;

import io.grpc.Status;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLException;

/**
 * Turns a failed call to the server into a sentence that says what to fix (ADR-0050). The raw gRPC
 * error for a certificate that doesn't name the dialled host is "UNAVAILABLE: io exception", which
 * points nowhere; the cause chain holds the TLS reason.
 */
public final class GrpcFailures {

  private GrpcFailures() {}

  /** What went wrong talking to {@code server} ({@code host:port}), and the likely fix. */
  public static String describe(Throwable failure, String server) {
    Status status = Status.fromThrowable(failure);
    Throwable tls = tlsCause(failure);
    if (tls != null) {
      return ("TLS handshake with the server at %s failed (%s). The server's certificate must name"
              + " the host the agent dials (PGLENS_SERVER_HOST): add it on the server with"
              + " PGLENS_TLS_EXTRA_SANS (e.g. DNS:pglens.internal) and give this agent that server's"
              + " ca.pem (PGLENS_SERVER_CA_CERT).")
          .formatted(server, rootMessage(tls));
    }
    return switch (status.getCode()) {
      case UNAUTHENTICATED ->
          "the server at %s refused the agent token (UNAUTHENTICATED): set PGLENS_AGENT_TOKEN to the"
                  .formatted(server)
              + " token shown when the database was added, or rotate it in Settings";
      case UNAVAILABLE ->
          "can't reach the server at %s (%s) — is it running, and is PGLENS_SERVER_HOST/PORT right?"
              .formatted(server, rootMessage(failure));
      case DEADLINE_EXCEEDED -> "the server at %s didn't answer in time".formatted(server);
      default ->
          "the server at %s answered %s%s"
              .formatted(
                  server,
                  status.getCode(),
                  status.getDescription() == null ? "" : ": " + status.getDescription());
    };
  }

  private static Throwable tlsCause(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof SSLException || c instanceof CertificateException) {
        return c;
      }
      if (c.getCause() == c) {
        break;
      }
    }
    return null;
  }

  private static String rootMessage(Throwable t) {
    Throwable root = t;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
  }
}
