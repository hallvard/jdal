package dev.jdal.cli;

import dev.jdal.core.Driver;
import dev.jdal.core.Jdal;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

@CommandDefinition(name = "drivers", description = "Lists the available drivers")
public class DriversCommand implements Command<CommandInvocation> {

  @Override
  public CommandResult execute(CommandInvocation invocation) {
    for (Driver d : Jdal.drivers()) {
      invocation.println(d.name() + (d.canCreate() ? " (rw): " : " (ro): ") + d.description());
    }
    return CommandResult.SUCCESS;
  }
}
