package com.pglens.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the PgLens central server. On boot, Spring Boot runs the Flyway migrations
 * against the metadata database (see {@code db/migration}). Hosts the gRPC ingest/validation
 * services and the {@code @Scheduled} advisory-lock-guarded analysis job (ADR-0028).
 */
@SpringBootApplication
@EnableScheduling
public class PglensServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(PglensServerApplication.class, args);
  }
}
