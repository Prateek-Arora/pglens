package com.pglens.server.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Owns the grpc-java {@link Server}'s lifecycle as a Spring bean (ADR-0025 — plain grpc-java, no
 * starter). Starts the server when the context starts and shuts it down gracefully when the context
 * closes. Binding to port 0 yields an OS-assigned port, readable via {@link #getPort()} (used by
 * tests).
 */
public class GrpcServerLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

  private final int port;
  private final List<ServerServiceDefinition> services;
  private Server server;
  private volatile boolean running;

  public GrpcServerLifecycle(int port, List<ServerServiceDefinition> services) {
    this.port = port;
    this.services = services;
  }

  @Override
  public void start() {
    ServerBuilder<?> builder = ServerBuilder.forPort(port);
    services.forEach(builder::addService);
    try {
      server = builder.build().start();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to start gRPC server on port " + port, e);
    }
    running = true;
    log.info("gRPC server listening on port {}", server.getPort());
  }

  @Override
  public void stop() {
    if (server != null) {
      server.shutdown();
      try {
        server.awaitTermination(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** The actual bound port (resolves an OS-assigned port when constructed with port 0). */
  public int getPort() {
    return server != null ? server.getPort() : port;
  }
}
