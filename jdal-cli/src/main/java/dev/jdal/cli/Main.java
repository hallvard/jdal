package dev.jdal.cli;

import java.util.Arrays;
import org.aesh.AeshConsoleRunner;
import org.aesh.AeshRuntimeRunner;
import org.aesh.command.CommandResult;

/**
 * Entry point with two modes: with arguments the command is run once ({@code jdal info file.gpkg});
 * without arguments, or with {@code -i}/{@code --interactive}, a REPL is started where the sub
 * commands are entered without the {@code jdal} prefix.
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    boolean interactive =
        args.length == 0
            || Arrays.asList(args).contains("-i")
            || Arrays.asList(args).contains("--interactive");
    if (interactive) {
      AeshConsoleRunner.builder()
          .commands(InfoCommand.class, ConvertCommand.class, DriversCommand.class)
          .prompt("jdal> ")
          .addExitCommand()
          .start();
      return;
    }
    try {
      CommandResult r = AeshRuntimeRunner.builder().command(JdalCommand.class).args(args).execute();
      System.exit(r.getExitCode());
    } catch (RuntimeException e) {
      // option conversion and validation failures
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      System.err.println("ERROR: " + cause.getMessage());
      System.exit(2);
    }
  }
}
