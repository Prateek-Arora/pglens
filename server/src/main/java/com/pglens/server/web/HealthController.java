package com.pglens.server.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated liveness for the HTTP listener (the gRPC side has its own Health service). */
@RestController
class HealthController {

  record Health(String status, String version) {}

  private final String version;

  HealthController(@Value("${pglens.version}") String version) {
    this.version = version;
  }

  @GetMapping("/api/v1/health")
  Health health() {
    return new Health("UP", version);
  }
}
