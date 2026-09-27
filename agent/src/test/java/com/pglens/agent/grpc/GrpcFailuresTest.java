package com.pglens.agent.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import java.io.IOException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class GrpcFailuresTest {

  @Test
  void aCertificateThatDoesntNameTheHostSaysHowToAddIt() {
    // What grpc-java reports when the dev certificate lacks host.docker.internal (found
    // 2026-09-27).
    SSLHandshakeException tls =
        new SSLHandshakeException("No subject alternative DNS name matching pglens.lan found.");
    Throwable failure =
        Status.UNAVAILABLE
            .withDescription("io exception")
            .withCause(new IOException(tls))
            .asRuntimeException();

    assertThat(GrpcFailures.describe(failure, "pglens.lan:9090"))
        .contains("TLS handshake with the server at pglens.lan:9090 failed")
        .contains("No subject alternative DNS name")
        .contains("PGLENS_TLS_EXTRA_SANS");
  }

  @Test
  void aRefusedTokenPointsAtTheTokenNotTheServer() {
    assertThat(GrpcFailures.describe(Status.UNAUTHENTICATED.asRuntimeException(), "server:9090"))
        .contains("refused the agent token")
        .contains("PGLENS_AGENT_TOKEN");
  }

  @Test
  void anUnreachableServerSaysSoWithTheCause() {
    Throwable failure =
        Status.UNAVAILABLE.withCause(new IOException("Connection refused")).asRuntimeException();
    assertThat(GrpcFailures.describe(failure, "server:9090"))
        .contains("can't reach the server at server:9090 (Connection refused)");
  }
}
