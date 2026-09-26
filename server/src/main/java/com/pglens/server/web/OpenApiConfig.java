package com.pglens.server.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI description served at {@code /api/v1/openapi.json} (ADR-0044): every endpoint needs a
 * bearer token (a session from {@code POST /api/v1/auth/login}, or a read-only API token) except
 * login and health.
 */
@Configuration
class OpenApiConfig {

  @Bean
  OpenAPI pglensOpenApi(@Value("${pglens.version}") String version) {
    return new OpenAPI()
        .info(
            new Info()
                .title("PgLens API")
                .version(version)
                .description(
                    "Read the slow-query history, plans, index recommendations and explanations"
                        + " PgLens keeps for each monitored database. queryid is a string"
                        + " everywhere (a signed 64-bit value). Numbers named measured… are summed"
                        + " pg_stat_statements deltas; estimated… and plannerCost… are planner"
                        + " estimates.")
                .license(new License().name("Apache-2.0")))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearer",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description("A session token (login) or an API token (read-only).")))
        .addSecurityItem(new SecurityRequirement().addList("bearer"));
  }
}
