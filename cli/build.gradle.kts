// :cli — the Spring Boot `pglens` command-line application.
//
// Spring owns DI/config; picocli owns argument parsing (via picocli core + a
// Spring-backed IFactory, not picocli-spring-boot-starter, which lags current
// Spring Boot). `bootJar` produces the runnable fat-jar.

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
  implementation(project(":engine"))
  implementation(libs.spring.boot.starter)
  implementation(libs.jackson.databind) // plain spring-boot-starter omits Jackson; --json needs it
  implementation(libs.picocli)

  testImplementation(libs.spring.boot.starter.test)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}
