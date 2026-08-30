// :proto — the gRPC/Protobuf contract (the spine everything else hangs off).
//
// This module owns pglens.proto and runs protoc + the grpc-java codegen to produce Java stubs, which
// :agent and :server depend on. We deliberately use plain grpc-java (grpc-netty-shaded / grpc-protobuf
// / grpc-stub) rather than a Spring-gRPC starter: the maintained Spring gRPC line requires Spring Boot
// 4.1 (we pin 3.5), and the community net.devh starter is unmaintained — the same reasoning as
// ADR-0012's plain-picocli choice. protoc's version MUST equal protobuf-java's so generated code and
// runtime agree.

import com.google.protobuf.gradle.id

plugins {
  `java-library`
  alias(libs.plugins.protobuf)
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

dependencies {
  api(platform(libs.grpc.bom))
  api(libs.protobuf.java)
  api(libs.grpc.protobuf)
  api(libs.grpc.stub)
  // grpc-generated stubs reference javax.annotation.Generated, which is not in the JDK.
  compileOnly(libs.tomcat.annotations.api)
}

protobuf {
  protoc {
    artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
  }
  plugins {
    id("grpc") {
      artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
    }
  }
  generateProtoTasks {
    all().forEach { task ->
      task.plugins {
        id("grpc")
      }
    }
  }
}

// The generated sources live under build/ — keep Spotless (which targets src/**) off them, and don't
// hand-format generated code.
