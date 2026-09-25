package com.pglens.engine.model;

import java.util.List;

/**
 * Identity of the scanned database, for the report header. {@code port} is null in reports older
 * than contract 1.2 (and for a {@code confirm} copy it is always set). {@code extensions} lists the
 * PgLens-relevant extensions actually installed on the target (e.g. {@code hypopg}, {@code
 * pg_stat_statements}) so the reader can see what validation was possible. Pure model — no I/O.
 */
public record TargetInfo(
    String host, Integer port, String database, String serverVersion, List<String> extensions) {

  public TargetInfo {
    extensions = extensions == null ? List.of() : List.copyOf(extensions);
  }
}
