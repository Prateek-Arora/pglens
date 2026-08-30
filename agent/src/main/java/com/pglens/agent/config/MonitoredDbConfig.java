package com.pglens.agent.config;

import com.pglens.engine.db.CatalogReader;
import com.pglens.engine.db.DataSources;
import com.pglens.engine.db.HypoPGValidator;
import com.pglens.engine.db.PlanCapturer;
import com.pglens.engine.db.StatsReader;
import com.pglens.engine.model.ConnectionTarget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Wires the agent's single connection to the <em>monitored</em> database and the engine DB-half
 * readers that run over it.
 *
 * <p>The connection is built by {@link DataSources#forScan} — the same tested path the CLI uses —
 * so it inherits {@code application_name=pglens}, the simple-query protocol EXPLAIN(GENERIC_PLAN)
 * needs, and (via {@link DataSources#applySessionGuards}, re-applied each cycle by the collector)
 * the DB-level {@code READ ONLY} guard and statement/lock timeouts. One held connection is what
 * HypoPG's session-local hypothetical indexes will require at the edge in Step 5; the sampler
 * shares it.
 */
@Configuration
public class MonitoredDbConfig {

  @Bean(destroyMethod = "destroy")
  public SingleConnectionDataSource monitoredDataSource(PglensAgentProperties props) {
    ConnectionTarget target = ConnectionTarget.parse(props.getMonitoredDbUrl());
    return DataSources.forScan(target);
  }

  @Bean
  public JdbcTemplate monitoredJdbcTemplate(SingleConnectionDataSource monitoredDataSource) {
    return new JdbcTemplate(monitoredDataSource);
  }

  @Bean
  public StatsReader statsReader(JdbcTemplate monitoredJdbcTemplate) {
    return new StatsReader(monitoredJdbcTemplate);
  }

  @Bean
  public CatalogReader catalogReader(JdbcTemplate monitoredJdbcTemplate) {
    return new CatalogReader(monitoredJdbcTemplate);
  }

  @Bean
  public PlanCapturer planCapturer(JdbcTemplate monitoredJdbcTemplate) {
    return new PlanCapturer(monitoredJdbcTemplate);
  }

  /**
   * Edge HypoPG validator (ADR-0023): the agent runs it next to the monitored DB for leased jobs.
   */
  @Bean
  public HypoPGValidator hypoPgValidator(JdbcTemplate monitoredJdbcTemplate) {
    return new HypoPGValidator(monitoredJdbcTemplate);
  }
}
