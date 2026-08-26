// :engine — the reusable analysis library.
//
// Package discipline (enforced by review now, ArchUnit later): the pure-analysis
// packages (model/parse/detect/candidate/rank) carry NO Spring imports so they
// unit-test with no container and Phase 2 can reuse them anywhere; only the db
// I/O half (and the PgLensEngine facade that wires it) touches spring-jdbc.

plugins {
  `java-library`
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

dependencies {
  implementation(platform(libs.spring.boot.dependencies))
  implementation(libs.spring.jdbc)
  implementation(libs.jackson.databind) // pure PlanParser uses Jackson (allowed; not Spring)
  runtimeOnly(libs.postgresql)

  testImplementation(platform(libs.spring.boot.dependencies))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testImplementation(platform(libs.testcontainers.bom))
  testImplementation(libs.testcontainers.junit)
  testImplementation(libs.testcontainers.postgresql)
  testRuntimeOnly(platform(libs.spring.boot.dependencies))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testRuntimeOnly(libs.postgresql)
}

// Fast unit tests run by default; Testcontainers integration tests (@Tag("it"), need Docker) run
// as a separate task that `check`/`build` include. Keeps the inner loop container-free.
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
      // Lets integration tests load the real monitored-db initdb scripts (schema lives in the
      // repo, not baked into the image — compose mounts it as a volume).
      systemProperty("pglens.repoRoot", rootDir.absolutePath)
    }

tasks.named("check") {
  dependsOn(integrationTest)
}
