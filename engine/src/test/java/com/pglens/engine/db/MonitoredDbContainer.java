package com.pglens.engine.db;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Builds and initializes a Testcontainers instance of the real monitored image for DB-layer
 * integration tests: {@code pg_stat_statements} preloaded, hypopg available, and the demo schema
 * loaded via the same initdb scripts compose uses (the schema is not baked into the image — compose
 * mounts it as a volume, so tests apply it over JDBC after startup).
 */
final class MonitoredDbContainer {

  private static final String IMAGE = "pglens/monitored-db:0.0.0";

  private MonitoredDbContainer() {}

  static PostgreSQLContainer<?> create() {
    return new PostgreSQLContainer<>(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("pglens_demo")
        .withCommand(
            "postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_stat_statements");
  }

  /** Runs the real 00_extensions.sql + 10_schema.sql against the started container. */
  static void initSchema(PostgreSQLContainer<?> db) {
    String initdb = System.getProperty("pglens.repoRoot", ".") + "/deploy/compose/monitored/initdb";
    if (!new File(initdb).isDirectory()) {
      throw new IllegalStateException(
          "initdb dir not found: " + initdb + " (pglens.repoRoot system property not set?)");
    }
    DriverManagerDataSource ds =
        new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    try (Connection c = ds.getConnection()) {
      ScriptUtils.executeSqlScript(c, new FileSystemResource(initdb + "/00_extensions.sql"));
      ScriptUtils.executeSqlScript(c, new FileSystemResource(initdb + "/10_schema.sql"));
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to initialize demo schema", e);
    }
  }
}
