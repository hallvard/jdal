package dev.jdal.cli;

import dev.jdal.core.Dataset;
import dev.jdal.core.Jdal;
import dev.jdal.core.JdalException;
import dev.jdal.utils.Info;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

@CommandDefinition(
    name = "info",
    description = "Lists layers, or summarizes/dumps them (like ogrinfo)")
public class InfoCommand implements Command<CommandInvocation> {

  @Option(
      shortName = 's',
      name = "summary",
      hasValue = false,
      description = "Summary only: no feature listing")
  boolean summary;

  @Option(
      shortName = 'a',
      name = "all",
      hasValue = false,
      description = "Describe all layers with their features")
  boolean all;

  @Argument(
      description = "Dataset",
      required = true,
      completer = org.aesh.command.impl.completer.FileOptionCompleter.class)
  String dataset;

  @Override
  public CommandResult execute(CommandInvocation invocation) {
    try (Dataset ds = Jdal.open(dataset)) {
      invocation.print(Info.describe(ds, summary, all || summary));
      return CommandResult.SUCCESS;
    } catch (JdalException e) {
      invocation.printErr("ERROR: " + e.getMessage());
      return CommandResult.FAILURE;
    }
  }
}
