package com.pglens.server.grpc;

import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the gRPC server: which services carry the {@link AuthInterceptor} and which don't. Ingest
 * (and, from Step 5, Validation) require a valid agent token; Health is unauthenticated.
 */
@Configuration
public class GrpcConfig {

  @Bean
  GrpcServerLifecycle grpcServerLifecycle(
      @Value("${pglens.grpc.port}") int port,
      IngestService ingestService,
      ValidationService validationService,
      HealthService healthService,
      AuthInterceptor authInterceptor) {
    List<ServerServiceDefinition> services =
        List.of(
            ServerInterceptors.intercept(ingestService, authInterceptor),
            ServerInterceptors.intercept(validationService, authInterceptor),
            healthService.bindService());
    return new GrpcServerLifecycle(port, services);
  }

  @Bean
  TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
    return new TransactionTemplate(txManager);
  }
}
