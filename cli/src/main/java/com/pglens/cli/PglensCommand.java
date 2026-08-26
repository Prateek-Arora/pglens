package com.pglens.cli;

import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Root {@code pglens} command. Holds no logic of its own — it dispatches to subcommands and, when
 * invoked bare, prints usage.
 */
@Component
@Command(
    name = "pglens",
    mixinStandardHelpOptions = true,
    version = "pglens 0.0.1",
    description = "Postgres slow-query & index advisor (HypoPG-validated).",
    subcommands = {ScanCommand.class, ExplainCommand.class})
class PglensCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  @Override
  public Integer call() {
    spec.commandLine().usage(System.out);
    return 0;
  }
}
