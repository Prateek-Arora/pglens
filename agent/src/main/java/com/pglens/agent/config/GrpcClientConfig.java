package com.pglens.agent.config;

import com.pglens.agent.grpc.IngestClient;
import com.pglens.agent.grpc.ValidationClient;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the gRPC channel to the PgLens server and the {@link IngestClient} over it.
 *
 * <p>Plaintext for now: transport security (mTLS) is deferred to Phase 6 (ADR-0027); authentication
 * this phase is the per-agent bearer token the client attaches to every call. Short-lived
 * client-streaming calls (one per interval) mean no long-idle stream for an LB to time out
 * (ADR-0023).
 */
@Configuration
public class GrpcClientConfig {

  @Bean(destroyMethod = "shutdownNow")
  public ManagedChannel serverChannel(PglensAgentProperties props) {
    return ManagedChannelBuilder.forAddress(
            props.getServer().getHost(), props.getServer().getPort())
        .usePlaintext()
        .build();
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
