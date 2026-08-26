// Root build: shared coordinates + Spotless (the single style source of truth, per CLAUDE.md).
// Per-module config lives in each module's build file — with just two modules a `buildSrc`
// convention plugin would be premature (KISS/YAGNI); revisit when the Phase 2 server lands.

plugins {
  alias(libs.plugins.spotless) apply false
}

allprojects {
  group = "com.pglens"
  version = "0.0.1-SNAPSHOT"
}

subprojects {
  apply(plugin = "com.diffplug.spotless")
  extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    java {
      target("src/**/*.java")
      googleJavaFormat()
      removeUnusedImports()
      trimTrailingWhitespace()
      endWithNewline()
    }
  }
}
