# PgLens collector agent image — a slim JRE running the Spring Boot bootJar.
#
# Runs next to a monitored Postgres, logging in as the read-only pglens_ro role (ADR-0030). The jar
# is built on the host (`./gradlew :agent:bootJar`, run by `make up`) and copied in; the build
# context is the repo root (see docker-compose.yml). The bootJar is named app.jar in
# agent/build.gradle.kts, so this copy is unambiguous and version-independent. Headless — no port.
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY agent/build/libs/app.jar app.jar

# Drop root — the agent only reads its jar and makes outbound connections. A fixed numeric UID (not
# just a name) so a Kubernetes runAsNonRoot/runAsUser can enforce non-root later (Phase 5).
RUN useradd --system --no-create-home --uid 10001 pglens
USER 10001

ENTRYPOINT ["java", "-jar", "app.jar"]
