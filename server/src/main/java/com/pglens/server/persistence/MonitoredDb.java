package com.pglens.server.persistence;

/**
 * A registered monitored database: its surrogate id and logical name (see {@code monitored_dbs}).
 */
public record MonitoredDb(long id, String name) {}
