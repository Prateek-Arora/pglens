package com.pglens.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the PgLens central server. On boot, Spring Boot runs the Flyway migrations
 * against the metadata database (see {@code db/migration}). Hosts the gRPC ingest/validation
 * services and the {@code @Scheduled} advisory-lock-guarded analysis job (ADR-0028). Boot's
 * in-memory default user is excluded: logins go through {@code AuthService} alone (ADR-0044).
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class PglensServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(PglensServerApplication.class, args);
  }
}
