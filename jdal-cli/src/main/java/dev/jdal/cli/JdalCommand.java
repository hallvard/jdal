package dev.jdal.cli;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

/** Root command: {@code jdal info|convert|drivers}. */
@CommandDefinition(
    name = "jdal",
    description = "Vector data utility (pure Java port of GDAL/OGR)",
    groupCommands = {InfoCommand.class, ConvertCommand.class, DriversCommand.class})
public class JdalCommand implements Command<CommandInvocation> {

  @Override
  public CommandResult execute(CommandInvocation invocation) {
    invocation.println(invocation.getHelpInfo());
    return CommandResult.SUCCESS;
  }
}
