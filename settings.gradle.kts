// PgLens Gradle build (introduced in Phase 1; deferred here per ADR-0010).
//
// Two modules, one clean boundary (see docs/architecture.md):
//   :engine — the reusable analysis library (pure-analysis packages + db I/O half).
//   :cli    — the Spring Boot `pglens` command-line app that wraps the engine.
// Phase 2's server will also depend on :engine and reuse its pure half unchanged.

plugins {
  // Auto-provisions the Java 21 toolchain even though this host runs JDK 17
  // (downloads a matching JDK on first build via the foojay disco API).
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "pglens"

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
}

// Phase 2 adds the collector/server split over gRPC:
//   :proto  — the .proto contract + generated stubs (the spine; depended on by agent + server).
//   :agent  — the collector (reuses :engine db half): scheduled sampler + gRPC ingest client.
//   :server — the central server (reuses :engine pure half): gRPC ingest, persistence, trends.
include("engine", "cli", "proto", "server", "agent")
