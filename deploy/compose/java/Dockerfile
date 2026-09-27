# syntax=docker/dockerfile:1.7
# PgLens's Java images — the server, the collector agent and the pglens CLI — from one build
# (ADR-0050). Build context: the repo root. Needs only Docker: Gradle runs in the build stage, on the
# build machine's own platform (the jars are platform-independent), so a multi-arch release build
# doesn't compile under emulation. Pick an image with --target server | agent | cli.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
COPY . .
# The Gradle cache (wrapper distribution + dependencies) survives between builds; `locked` keeps the
# server and agent builds from racing on it when compose builds them together.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon --quiet :server:bootJar :agent:bootJar :cli:bootJar \
    && mkdir /out \
    && cp server/build/libs/app.jar /out/server.jar \
    && cp agent/build/libs/app.jar /out/agent.jar \
    && find cli/build/libs -name 'cli-*.jar' ! -name '*-plain.jar' -exec cp {} /out/cli.jar \;

# Every image drops root: a fixed numeric UID (not just a name) so Kubernetes runAsNonRoot/runAsUser
# can enforce it later (Phase 5).
FROM eclipse-temurin:21-jre-jammy AS runtime
RUN useradd --system --no-create-home --uid 10001 pglens
WORKDIR /app

# The collector: runs next to a monitored Postgres as a read-only role (ADR-0030). Headless — no port.
FROM runtime AS agent
COPY --from=build /out/agent.jar app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "app.jar"]

# The one-shot CLI: `docker run --rm -v "$PWD:/work" <image> scan postgresql://…`. Reads and writes
# report files in /work.
FROM runtime AS cli
COPY --from=build /out/cli.jar /app/pglens.jar
WORKDIR /work
USER 10001
ENTRYPOINT ["java", "-jar", "/app/pglens.jar"]

# The server: gRPC ingest/validation (TLS) on 9090, the HTTP API on 8080. The JRE base image (Ubuntu)
# ships bash, which the compose healthcheck's /dev/tcp probe needs.
FROM runtime AS server
COPY --from=build /out/server.jar app.jar
USER 10001
EXPOSE 9090 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
