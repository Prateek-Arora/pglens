package com.pglens.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.InsecureChannelCredentials;
import io.grpc.TlsChannelCredentials;
import io.grpc.testing.TlsTesting;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The agent's channel security (ADR-0044): TLS unless plaintext is explicitly asked for. */
class GrpcClientConfigTest {

  @TempDir File dir;

  @Test
  void tlsWithTheJvmTrustStoreIsTheDefault() {
    PglensAgentProperties.Server server = new PglensAgentProperties.Server();

    assertThat(server.isPlaintext()).isFalse();
    assertThat(GrpcClientConfig.credentials(server)).isInstanceOf(TlsChannelCredentials.class);
  }

  @Test
  void aConfiguredCaIsTrusted() throws Exception {
    File ca = new File(dir, "ca.pem");
    try (InputStream in = TlsTesting.loadCert("ca.pem")) {
      Files.copy(in, ca.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    PglensAgentProperties.Server server = new PglensAgentProperties.Server();
    server.setCaCert(ca.getPath());

    assertThat(GrpcClientConfig.credentials(server)).isInstanceOf(TlsChannelCredentials.class);
  }

  @Test
  void aMissingCaFailsFastWithTheSettingName() {
    PglensAgentProperties.Server server = new PglensAgentProperties.Server();
    server.setCaCert(new File(dir, "missing.pem").getPath());

    assertThatThrownBy(() -> GrpcClientConfig.credentials(server))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PGLENS_SERVER_CA_CERT");
  }

  @Test
  void plaintextOnlyWhenExplicitlyAsked() {
    PglensAgentProperties.Server server = new PglensAgentProperties.Server();
    server.setPlaintext(true);

    assertThat(GrpcClientConfig.credentials(server)).isInstanceOf(InsecureChannelCredentials.class);
  }
}
