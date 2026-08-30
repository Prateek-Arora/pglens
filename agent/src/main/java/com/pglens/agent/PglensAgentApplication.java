package com.pglens.agent;

import com.pglens.agent.config.PglensAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The PgLens collector agent. A thin, dial-out Spring Boot process that samples {@code
 * pg_stat_statements} on the monitored database and streams the raw cumulative counters to the
 * server over gRPC (ADR-0023 {@code a-pull}; ADR-0024 server-side deltas).
 *
 * <p>Its only datasource is the <em>monitored</em> DB, built by hand from {@link
 * com.pglens.engine.db.DataSources} so it carries the read-only session guards and the
 * simple-query-protocol setting EXPLAIN(GENERIC_PLAN) needs — so Spring Boot's {@link
 * DataSourceAutoConfiguration} (which would demand a {@code spring.datasource.url}) is excluded.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(PglensAgentProperties.class)
@EnableScheduling
public class PglensAgentApplication {

  public static void main(String[] args) {
    SpringApplication.run(PglensAgentApplication.class, args);
  }
}
