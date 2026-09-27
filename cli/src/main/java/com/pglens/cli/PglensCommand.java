package com.pglens.cli;

import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
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
    versionProvider = PglensCommand.BuildVersion.class,
    description = "Postgres slow-query & index advisor (HypoPG-validated).",
    subcommands = {ScanCommand.class, ExplainCommand.class, ConfirmCommand.class})
class PglensCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  @Override
  public Integer call() {
    spec.commandLine().usage(System.out);
    return 0;
  }

  /**
   * The build's version, from the jar's manifest (Spring Boot writes it); "dev" when unpackaged.
   */
  static final class BuildVersion implements IVersionProvider {
    @Override
    public String[] getVersion() {
      String v = PglensCommand.class.getPackage().getImplementationVersion();
      return new String[] {"pglens " + (v == null ? "dev" : v)};
    }
  }
}
