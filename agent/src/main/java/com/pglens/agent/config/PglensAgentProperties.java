package com.pglens.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent configuration ({@code pglens.agent.*}). Identity + the two endpoints the agent bridges: the
 * monitored database it reads and the PgLens server it streams to. All values have env-var-backed
 * defaults in {@code application.yml} so a container run needs no config file.
 */
@ConfigurationProperties(prefix = "pglens.agent")
public class PglensAgentProperties {

  /**
   * libpq-style or {@code jdbc:} URL of the monitored database (read as a dedicated read-only
   * role).
   */
  private String monitoredDbUrl;

  /** Logical identity of the monitored DB; must match {@code monitored_dbs.name} on the server. */
  private String dbName;

  /** Host as the agent sees it — diagnostic only, carried through for the server's records. */
  private String dbHost;

  /** Per-agent bearer token (ADR-0027); the server hashes it to resolve this db. */
  private String token;

  private final Server server = new Server();
  private final Sample sample = new Sample();
  private final Validation validation = new Validation();

  public String getMonitoredDbUrl() {
    return monitoredDbUrl;
  }

  public void setMonitoredDbUrl(String monitoredDbUrl) {
    this.monitoredDbUrl = monitoredDbUrl;
  }

  public String getDbName() {
    return dbName;
  }

  public void setDbName(String dbName) {
    this.dbName = dbName;
  }

  public String getDbHost() {
    return dbHost;
  }

  public void setDbHost(String dbHost) {
    this.dbHost = dbHost;
  }

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public Server getServer() {
    return server;
  }

  public Sample getSample() {
    return sample;
  }

  public Validation getValidation() {
    return validation;
  }

  /** The PgLens server's gRPC endpoint. */
  public static class Server {
    private String host = "localhost";
    private int port = 9090;

    public String getHost() {
      return host;
    }

    public void setHost(String host) {
      this.host = host;
    }

    public int getPort() {
      return port;
    }

    public void setPort(int port) {
      this.port = port;
    }
  }

  /** Sampling cadence and bounds. */
  public static class Sample {
    /** Delay between the end of one sample cycle and the start of the next. */
    private long intervalMs = 60_000;

    /** Upper bound on statements sampled per interval (ranked by total exec time). */
    private int topN = 200;

    /** Minimum cumulative calls for a statement to be sampled at all. */
    private long minCalls = 1;

    public long getIntervalMs() {
      return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
      this.intervalMs = intervalMs;
    }

    public int getTopN() {
      return topN;
    }

    public void setTopN(int topN) {
      this.topN = topN;
    }

    public long getMinCalls() {
      return minCalls;
    }

    public void setMinCalls(long minCalls) {
      this.minCalls = minCalls;
    }
  }

  /** Edge-validation cadence and batch size (the a-pull lease loop). */
  public static class Validation {
    /** Delay between validation lease cycles. */
    private long intervalMs = 30_000;

    /** Max jobs to lease (and validate) per cycle. */
    private int maxLease = 10;

    public long getIntervalMs() {
      return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
      this.intervalMs = intervalMs;
    }

    public int getMaxLease() {
      return maxLease;
    }

    public void setMaxLease(int maxLease) {
      this.maxLease = maxLease;
    }
  }
}
