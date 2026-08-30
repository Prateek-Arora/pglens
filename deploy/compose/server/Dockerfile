# PgLens server image — a slim JRE running the Spring Boot bootJar.
#
# The jar is built on the host (`./gradlew :server:bootJar`, run by `make up`) and copied in; the
# build context is the repo root (see docker-compose.yml) so this path resolves. The bootJar is
# named app.jar in server/build.gradle.kts, so this copy is unambiguous and version-independent.
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY server/build/libs/app.jar app.jar

# Drop root — the server only needs to read its jar and open a socket. A fixed numeric UID (not just
# a name) so a Kubernetes runAsNonRoot/runAsUser can enforce non-root later (Phase 5).
RUN useradd --system --no-create-home --uid 10001 pglens
USER 10001

# gRPC ingest/validation listener (matches pglens.grpc.port).
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
