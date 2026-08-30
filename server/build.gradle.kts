// :server — the central PgLens server (Spring Boot).
//
// Reuses the :engine PURE half (parse/detect/candidate/rank) — never the db I/O half, which lives on
// the agent (ADR-0023). Owns the metadata schema (Flyway migrations), gRPC ingest + validation
// services (plain grpc-java, ADR-0025), the scheduled analysis job, and trend queries. `bootJar`
// produces the runnable server image.

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
  implementation(project(":engine")) // pure half only (detect/candidate/rank + models)
  implementation(project(":proto")) // brings grpc-protobuf/grpc-stub/protobuf-java (api on :proto)
  implementation(libs.spring.boot.starter)
  implementation(libs.spring.boot.starter.jdbc)
  implementation(libs.jackson.databind) // plan_json handling reuses engine's Jackson-based PlanParser
  implementation(libs.flyway.core)
  runtimeOnly(libs.flyway.database.postgresql) // Flyway 10+ splits Postgres support into this artifact
  runtimeOnly(libs.postgresql)

  // gRPC server transport (used from Step 3; the stubs themselves come from :proto).
  implementation(platform(libs.grpc.bom))
  implementation(libs.grpc.netty.shaded)

  testImplementation(libs.spring.boot.starter.test)
  testImplementation(platform(libs.testcontainers.bom))
  testImplementation(libs.testcontainers.junit)
  testImplementation(libs.testcontainers.postgresql)
  // The Step-10 full-loop IT drives the REAL agent runtime components (SampleCollector,
  // ValidationRunner, the gRPC clients) against a booted server — test scope only, no runtime
  // coupling (:agent never depends on :server, so there is no cycle).
  testImplementation(project(":agent"))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Give the bootJar a fixed name so the Docker image copies `build/libs/app.jar` unambiguously,
// independent of version or the coexisting plain jar (Step 9). The plain jar stays enabled for
// symmetry with :agent (which must stay consumable as a test dependency).
tasks.bootJar { archiveFileName.set("app.jar") }

// Fast unit tests run by default; Testcontainers integration tests (@Tag("it"), need Docker) run as a
// separate task that `check`/`build` include — mirrors :engine, keeping the inner loop container-free.
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
