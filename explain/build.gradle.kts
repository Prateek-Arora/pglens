// :explain — plain-language explanations of PgLens's recommendations (Phase 3, ADR-0043).
//
// Pure facts/cards/template/guard code plus a plain java.net.http client for any
// OpenAI-compatible endpoint. No Spring: the CLI and the server both wire it. Depends on
// :engine only for its model records — :engine never depends on this module, so the
// deterministic core can't reach an LLM.

plugins {
  `java-library`
  `java-test-fixtures` // StubLlmServer, shared with :server's integration tests
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

dependencies {
  api(project(":engine"))
  implementation(platform(libs.spring.boot.dependencies))
  implementation(libs.jackson.databind)

  testFixturesImplementation(platform(libs.spring.boot.dependencies))
  testFixturesImplementation(libs.jackson.databind)

  testImplementation(platform(libs.spring.boot.dependencies))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testRuntimeOnly(platform(libs.spring.boot.dependencies))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}

// The real-model eval (docs/llm-eval.md). Needs a running OpenAI-compatible endpoint, so it is
// never part of `build`/CI: `./gradlew :explain:llmEval -Psplit=dev [-Pdocs=true]`.
tasks.register<JavaExec>("llmEval") {
  description = "Runs the frozen eval cases through a real LLM (needs one running)."
  group = "verification"
  classpath = sourceSets.test.get().runtimeClasspath
  mainClass = "com.pglens.explain.EvalRunner"
  javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
  args =
      listOf(
          providers.gradleProperty("split").getOrElse("dev"),
          providers.gradleProperty("model").getOrElse(""),
          providers.gradleProperty("llmUrl").getOrElse(""),
          layout.buildDirectory.dir("llm-eval").get().asFile.absolutePath,
          providers.gradleProperty("docs").getOrElse("false"),
          rootDir.resolve("server/src/main/resources/knowledge/pg16-docs.jsonl").absolutePath)
}
