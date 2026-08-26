package com.pglens.cli;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import picocli.CommandLine;

/**
 * Spring Boot entry point for the {@code pglens} CLI.
 *
 * <p>Spring owns dependency injection and configuration; picocli owns argument parsing. Commands
 * are Spring beans resolved through {@link SpringPicocliFactory}, so they can inject engine
 * collaborators. On startup the {@link CommandLineRunner} hands the raw args to picocli and exposes
 * picocli's exit code to Spring Boot via {@link ExitCodeGenerator}.
 */
@SpringBootApplication
public class PglensApplication implements CommandLineRunner, ExitCodeGenerator {

  private final PglensCommand root;
  private final CommandLine.IFactory factory;
  private int exitCode;

  public PglensApplication(PglensCommand root, CommandLine.IFactory factory) {
    this.root = root;
    this.factory = factory;
  }

  @Override
  public void run(String... args) {
    exitCode = new CommandLine(root, factory).execute(args);
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }

  public static void main(String[] args) {
    System.exit(SpringApplication.exit(SpringApplication.run(PglensApplication.class, args)));
  }
}
