package dev.jdal.cli;

import dev.jdal.core.JdalException;
import dev.jdal.geom.Envelope;
import dev.jdal.srs.SpatialReference;
import dev.jdal.utils.Translate;
import dev.jdal.utils.TranslateOptions;
import java.util.List;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

@CommandDefinition(
    name = "convert",
    description = "Copies a dataset to another format/CRS (like ogr2ogr): convert SRC DST")
public class ConvertCommand implements Command<CommandInvocation> {

  @Option(
      shortName = 'f',
      name = "format",
      description = "Output driver name (default: from extension)",
      completer = Converters.FormatCompleter.class,
      validator = Converters.FormatValidator.class)
  String format;

  @Option(
      name = "t_srs",
      description = "Reproject to this CRS, e.g. EPSG:32633",
      converter = Converters.SrsConverter.class)
  SpatialReference targetSrs;

  @Option(
      name = "s_srs",
      description = "Override source CRS",
      converter = Converters.SrsConverter.class)
  SpatialReference sourceSrs;

  @Option(name = "nln", description = "Output layer name")
  String layerName;

  @Option(
      name = "spat",
      description = "Spatial filter: minx,miny,maxx,maxy",
      converter = Converters.EnvelopeConverter.class)
  Envelope spat;

  @Option(
      name = "spat_wkt",
      description =
          "Spatial filter: only features intersecting this geometry (WKT, in the source CRS)")
  String spatWkt;

  @OptionList(
      name = "lco",
      description = "Layer creation option KEY=VALUE (repeatable)",
      converter = Converters.KeyValueConverter.class,
      completer = Converters.LayerCreationCompleter.class)
  List<Converters.KeyValue> layerOptions;

  @OptionList(
      name = "dsco",
      description = "Dataset creation option KEY=VALUE (repeatable)",
      converter = Converters.KeyValueConverter.class,
      completer = Converters.DatasetCreationCompleter.class)
  List<Converters.KeyValue> datasetOptions;

  @OptionList(
      name = "oo",
      description = "Open option for the source KEY=VALUE (repeatable)",
      converter = Converters.KeyValueConverter.class,
      completer = Converters.OpenOptionCompleter.class)
  List<Converters.KeyValue> openOptions;

  @Arguments(
      description = "SRC DST [layers...]",
      completer = Converters.SourceAndLayersCompleter.class)
  List<String> args;

  @Override
  public CommandResult execute(CommandInvocation invocation) {
    if (args == null || args.size() < 2) {
      invocation.printErr("usage: jdal convert [options] SRC DST [layer...]");
      return CommandResult.USAGE_ERROR;
    }
    warnAboutOptions(invocation);
    try {
      TranslateOptions o =
          TranslateOptions.defaults()
              .withDriver(format)
              .withNewLayerName(layerName)
              .withLayers(args.subList(2, args.size()))
              .withLayerOptions(keyValues(layerOptions))
              .withDatasetOptions(keyValues(datasetOptions))
              .withOpenOptions(keyValues(openOptions));
      if (targetSrs != null) o = o.withTargetSrs(targetSrs);
      if (sourceSrs != null) o = o.withSourceSrs(sourceSrs);
      if (spat != null) o = o.withBbox(spat);
      if (spatWkt != null) {
        o =
            o.withSpatialFilter(
                new dev.jdal.core.SpatialFilter(
                    dev.jdal.core.SpatialOp.INTERSECTS, dev.jdal.geom.Wkt.read(spatWkt)));
      }
      long n = Translate.translate(args.get(0), args.get(1), o);
      invocation.println(n + " features written");
      return CommandResult.SUCCESS;
    } catch (JdalException | IllegalArgumentException | dev.jdal.srs.TransformException e) {
      invocation.printErr("ERROR: " + e.getMessage());
      return CommandResult.FAILURE;
    }
  }

  private static java.util.Map<String, String> keyValues(List<Converters.KeyValue> list) {
    java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
    if (list != null) {
      for (Converters.KeyValue kv : list) m.put(kv.key(), kv.value());
    }
    return m;
  }

  /** The driver that will write DST: the one named by {@code -f}, else the one matching DST. */
  java.util.Optional<dev.jdal.core.Driver> outputDriver() {
    if (format != null && !format.isEmpty()) return dev.jdal.core.Jdal.driver(format);
    return args != null && args.size() >= 2
        ? dev.jdal.core.Jdal.driverForOutput(args.get(1))
        : java.util.Optional.empty();
  }

  /** The driver that will read SRC. */
  java.util.Optional<dev.jdal.core.Driver> inputDriver() {
    if (args == null || args.isEmpty()) return java.util.Optional.empty();
    dev.jdal.core.OpenInfo info = new dev.jdal.core.OpenInfo(args.getFirst(), java.util.Map.of());
    for (dev.jdal.core.Driver d : dev.jdal.core.Jdal.drivers()) {
      try {
        if (d.identify(info)) return java.util.Optional.of(d);
      } catch (RuntimeException e) {
        // identification must not fail the command line
      }
    }
    return java.util.Optional.empty();
  }

  /** Like GDAL: unknown option keys and invalid values are warnings, not errors. */
  private void warnAboutOptions(CommandInvocation invocation) {
    java.util.List<String> problems = new java.util.ArrayList<>();
    outputDriver()
        .ifPresent(
            d -> {
              problems.addAll(
                  dev.jdal.core.OptionDef.problems(
                      "layer creation",
                      d.name(),
                      d.layerCreationOptions(),
                      keyValues(layerOptions)));
              problems.addAll(
                  dev.jdal.core.OptionDef.problems(
                      "dataset creation",
                      d.name(),
                      d.datasetCreationOptions(),
                      keyValues(datasetOptions)));
            });
    inputDriver()
        .ifPresent(
            d ->
                problems.addAll(
                    dev.jdal.core.OptionDef.problems(
                        "open", d.name(), d.openOptions(), keyValues(openOptions))));
    problems.forEach(p -> invocation.printErr("Warning: " + p));
  }
}
