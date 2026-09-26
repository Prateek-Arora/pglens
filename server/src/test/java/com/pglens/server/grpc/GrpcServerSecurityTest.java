package com.pglens.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pglens.proto.v1.HealthGrpc;
import com.pglens.proto.v1.HealthRequest;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ServerCredentials;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** gRPC transport security (ADR-0044): TLS by default, plaintext refused, fail fast. No DB. */
class GrpcServerSecurityTest {

  private GrpcServerLifecycle server;
  private ManagedChannel channel;

  @AfterEach
  void tearDown() throws InterruptedException {
    if (channel != null) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
    if (server != null) {
      server.stop();
    }
  }

  @Test
  void tlsIsTheDefaultAndAMissingCertificateFailsFastWithTheFix() {
    assertThatThrownBy(() -> GrpcServerSecurity.credentials(false, "", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PGLENS_GRPC_TLS_CERT")
        .hasMessageContaining("PGLENS_GRPC_PLAINTEXT=true");
  }

  @Test
  void anUnreadableCertificateFailsFast() {
    assertThatThrownBy(
            () ->
                GrpcServerSecurity.credentials(
                    false, "/nonexistent/cert.pem", GrpcTestTls.SERVER_KEY.getPath()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not readable");
  }

  @Test
  void aTlsClientThatTrustsTheCaIsServed() {
    start(tls());
    channel = GrpcTestTls.channel(server.getPort()).build();

    assertThat(health(channel)).isTrue();
  }

  @Test
  void aPlaintextClientIsRefusedByATlsServer() {
    start(tls());
    channel =
        Grpc.newChannelBuilderForAddress(
                "localhost", server.getPort(), InsecureChannelCredentials.create())
            .build();

    assertThatThrownBy(() -> health(channel))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
  }

  @Test
  void aClientThatDoesNotTrustTheCertificateIsRefused() {
    start(tls());
    // The JVM's default trust store does not contain the test CA.
    channel =
        Grpc.newChannelBuilderForAddress(
                "localhost", server.getPort(), TlsChannelCredentials.create())
            .overrideAuthority(GrpcTestTls.AUTHORITY)
            .build();

    assertThatThrownBy(() -> health(channel)).isInstanceOf(StatusRuntimeException.class);
  }

  @Test
  void plaintextOnlyWhenExplicitlyAsked() {
    ServerCredentials credentials = GrpcServerSecurity.credentials(true, "", "");
    assertThat(credentials).isInstanceOf(InsecureServerCredentials.class);

    start(credentials);
    channel =
        Grpc.newChannelBuilderForAddress(
                "localhost", server.getPort(), InsecureChannelCredentials.create())
            .build();
    assertThat(health(channel)).isTrue();
  }

  private static ServerCredentials tls() {
    return GrpcServerSecurity.credentials(
        false, GrpcTestTls.SERVER_CERT.getPath(), GrpcTestTls.SERVER_KEY.getPath());
  }

  private void start(ServerCredentials credentials) {
    server =
        new GrpcServerLifecycle(0, credentials, List.of(new HealthService("test").bindService()));
    server.start();
  }

  private static boolean health(ManagedChannel channel) {
    return HealthGrpc.newBlockingStub(channel)
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .check(HealthRequest.getDefaultInstance())
        .getServing();
  }
}
