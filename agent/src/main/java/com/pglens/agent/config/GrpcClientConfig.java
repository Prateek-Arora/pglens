package com.pglens.agent.config;

import com.pglens.agent.grpc.IngestClient;
import com.pglens.agent.grpc.ValidationClient;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.TlsChannelCredentials;
import java.io.File;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the gRPC channel to the PgLens server and the clients over it.
 *
 * <p>TLS by default (ADR-0044): the per-agent bearer token rides every call, so it must not travel
 * in cleartext. Plaintext needs an explicit {@code pglens.agent.server.plaintext=true}. Short-lived
 * client-streaming calls (one per interval) mean no long-idle stream for an LB to time out
 * (ADR-0023).
 */
@Configuration
public class GrpcClientConfig {

  private static final Logger log = LoggerFactory.getLogger(GrpcClientConfig.class);

  @Bean(destroyMethod = "shutdownNow")
  public ManagedChannel serverChannel(PglensAgentProperties props) {
    PglensAgentProperties.Server server = props.getServer();
    return channel(server.getHost(), server.getPort(), server);
  }

  /** The channel for {@code host:port} with {@code server}'s security settings. */
  public static ManagedChannel channel(String host, int port, PglensAgentProperties.Server server) {
    ManagedChannelBuilder<?> builder =
        Grpc.newChannelBuilderForAddress(host, port, credentials(server));
    if (server.getAuthority() != null && !server.getAuthority().isBlank()) {
      builder.overrideAuthority(server.getAuthority());
    }
    if (server.isPlaintext()) {
      log.warn(
          "connecting to the PgLens server at {}:{} WITHOUT TLS (pglens.agent.server.plaintext"
              + "=true): the agent token travels unencrypted",
          host,
          port);
    }
    return builder.build();
  }

  /**
   * @throws IllegalStateException the configured CA certificate is missing or unreadable
   */
  static ChannelCredentials credentials(PglensAgentProperties.Server server) {
    if (server.isPlaintext()) {
      return InsecureChannelCredentials.create();
    }
    String ca = server.getCaCert();
    if (ca == null || ca.isBlank()) {
      return TlsChannelCredentials.create(); // the JVM's default trust store
    }
    File caFile = new File(ca);
    if (!caFile.canRead()) {
      throw new IllegalStateException(
          "pglens.agent.server.ca-cert (PGLENS_SERVER_CA_CERT) is not readable: " + ca);
    }
    try {
      return TlsChannelCredentials.newBuilder().trustManager(caFile).build();
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "could not load the server CA certificate " + ca + " (" + e.getMessage() + ")", e);
    }
  }

  @Bean
  public IngestClient ingestClient(ManagedChannel serverChannel, PglensAgentProperties props) {
    return new IngestClient(serverChannel, props.getToken());
  }

  @Bean
  public ValidationClient validationClient(
      ManagedChannel serverChannel, PglensAgentProperties props) {
    return new ValidationClient(serverChannel, props.getToken());
  }
}
