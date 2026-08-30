// :agent — the PgLens collector (Spring Boot).
//
// Runs next to a monitored Postgres. Reuses the :engine DB I/O half (StatsReader, PlanCapturer,
// DataSources' read-only guards) — never the pure analysis half, which lives on the server
// (ADR-0023). On a schedule it reads cumulative pg_stat_statements + the global stats_reset and
// client-streams a SampleBatch to the server over gRPC (plain grpc-java, ADR-0025). `bootJar`
// produces the runnable agent image.

plugins {
  java
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.dependency.management)
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

dependencies {
  implementation(project(":engine")) // db half: StatsReader / PlanCapturer / CatalogReader + DataSources
  implementation(project(":proto")) // brings grpc-protobuf/grpc-stub/protobuf-java (api on :proto)
  implementation(libs.spring.boot.starter)
  implementation(libs.spring.boot.starter.jdbc) // JdbcTemplate over the monitored DB (autoconfig excluded)
  runtimeOnly(libs.postgresql)

  // gRPC client transport (the stubs themselves come from :proto).
  implementation(platform(libs.grpc.bom))
  implementation(libs.grpc.netty.shaded)

  testImplementation(libs.spring.boot.starter.test)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Give the bootJar a fixed name so the Docker image copies `build/libs/app.jar` unambiguously,
// independent of version or the coexisting plain jar (Step 9). The plain library jar stays enabled
// so the :server full-loop IT can consume :agent as a test dependency (a disabled jar makes the
// module's runtime variant empty).
tasks.bootJar { archiveFileName.set("app.jar") }

// Fast unit tests run by default; Testcontainers integration tests (@Tag("it"), need Docker) run as a
// separate task that `check`/`build` include — mirrors :engine/:server. The full agent+server+DB
// integration test lands in Step 10.
tasks.test {
  useJUnitPlatform { excludeTags("it") }
}

val integrationTest by
    tasks.registering(Test::class) {
      description = "Runs @Tag(\"it\") Testcontainers integration tests (requires Docker)."
      group = "verification"
      testClassesDirs = sourceSets.test.get().output.classesDirs
      classpath = sourceSets.test.get().runtimeClasspath
      useJUnitPlatform { includeTags("it") }
      shouldRunAfter(tasks.test)
    }

tasks.named("check") {
  dependsOn(integrationTest)
}
