package dev.jdal.cli;

import dev.jdal.core.Dataset;
import dev.jdal.core.Driver;
import dev.jdal.core.Jdal;
import dev.jdal.core.OptionDef;
import dev.jdal.geom.Envelope;
import dev.jdal.srs.SpatialReference;
import java.util.List;
import org.aesh.command.completer.CompleterInvocation;
import org.aesh.command.completer.OptionCompleter;
import org.aesh.command.converter.Converter;
import org.aesh.command.converter.ConverterInvocation;
import org.aesh.command.validator.OptionValidator;
import org.aesh.command.validator.OptionValidatorException;
import org.aesh.command.validator.ValidatorInvocation;

/** Converters, completers and validators for the command line options. */
final class Converters {

  private Converters() {}

  /** A {@code KEY=VALUE} creation or open option. */
  record KeyValue(String key, String value) {}

  /** {@code minx,miny,maxx,maxy}. */
  public static final class EnvelopeConverter implements Converter<Envelope, ConverterInvocation> {
    @Override
    public Envelope convert(ConverterInvocation in) throws OptionValidatorException {
      String[] p = in.getInput().split(",");
      if (p.length != 4) {
        throw new OptionValidatorException(
            "Expected minx,miny,maxx,maxy but got '" + in.getInput() + "'");
      }
      try {
        return new Envelope(
            Double.parseDouble(p[0].trim()),
            Double.parseDouble(p[1].trim()),
            Double.parseDouble(p[2].trim()),
            Double.parseDouble(p[3].trim()));
      } catch (IllegalArgumentException e) {
        throw new OptionValidatorException(
            "Invalid bounding box '" + in.getInput() + "': " + e.getMessage());
      }
    }
  }

  /** {@code EPSG:n}, a PROJ string or WKT. */
  public static final class SrsConverter
      implements Converter<SpatialReference, ConverterInvocation> {
    @Override
    public SpatialReference convert(ConverterInvocation in) throws OptionValidatorException {
      try {
        return SpatialReference.fromUserInput(in.getInput());
      } catch (RuntimeException e) {
        throw new OptionValidatorException(
            "Unknown CRS '" + in.getInput() + "': " + e.getMessage());
      }
    }
  }

  public static final class KeyValueConverter implements Converter<KeyValue, ConverterInvocation> {
    @Override
    public KeyValue convert(ConverterInvocation in) throws OptionValidatorException {
      String s = in.getInput();
      int eq = s.indexOf('=');
      if (eq <= 0) throw new OptionValidatorException("Expected KEY=VALUE but got '" + s + "'");
      return new KeyValue(s.substring(0, eq), s.substring(eq + 1));
    }
  }

  /** Completes the names of the drivers that can create datasets. */
  public static final class FormatCompleter implements OptionCompleter<CompleterInvocation> {
    @Override
    public void complete(CompleterInvocation in) {
      String given =
          in.getGivenCompleteValue() == null ? "" : in.getGivenCompleteValue().toLowerCase();
      for (Driver d : Jdal.drivers()) {
        if (d.canCreate() && d.name().toLowerCase().startsWith(given))
          in.addCompleterValue(d.name());
      }
    }
  }

  /** Rejects drivers that do not exist or cannot create datasets. */
  public static final class FormatValidator
      implements OptionValidator<ValidatorInvocation<String, ?>> {
    @Override
    public void validate(ValidatorInvocation<String, ?> in) throws OptionValidatorException {
      String name = in.getValue();
      if (name == null) return;
      for (Driver d : Jdal.drivers()) {
        if (d.name().equalsIgnoreCase(name)) {
          if (!d.canCreate())
            throw new OptionValidatorException("Driver '" + d.name() + "' cannot create datasets");
          return;
        }
      }
      throw new OptionValidatorException("Unknown driver '" + name + "', see 'drivers'");
    }
  }

  /** Completes {@code KEY=VALUE} options from what the relevant driver declares. */
  abstract static class OptionKeyCompleter implements OptionCompleter<CompleterInvocation> {

    abstract List<OptionDef> defs(ConvertCommand command);

    @Override
    public void complete(CompleterInvocation in) {
      String given = in.getGivenCompleteValue() == null ? "" : in.getGivenCompleteValue();
      List<OptionDef> defs = defs((ConvertCommand) in.getCommand());
      int eq = given.indexOf('=');
      if (eq < 0) {
        for (OptionDef d : defs) {
          if (d.name().regionMatches(true, 0, given, 0, given.length())) {
            in.addCompleterValue(d.name() + "=");
          }
        }
        in.setAppendSpace(false);
        return;
      }
      String key = given.substring(0, eq);
      for (OptionDef d : defs) {
        if (!d.name().equalsIgnoreCase(key)) continue;
        for (String v : d.values()) {
          String candidate = d.name() + "=" + v;
          if (candidate.regionMatches(true, 0, given, 0, given.length()))
            in.addCompleterValue(candidate);
        }
      }
    }
  }

  public static final class LayerCreationCompleter extends OptionKeyCompleter {
    @Override
    List<OptionDef> defs(ConvertCommand c) {
      return c.outputDriver().map(Driver::layerCreationOptions).orElse(List.of());
    }
  }

  public static final class DatasetCreationCompleter extends OptionKeyCompleter {
    @Override
    List<OptionDef> defs(ConvertCommand c) {
      return c.outputDriver().map(Driver::datasetCreationOptions).orElse(List.of());
    }
  }

  public static final class OpenOptionCompleter extends OptionKeyCompleter {
    @Override
    List<OptionDef> defs(ConvertCommand c) {
      return c.inputDriver().map(Driver::openOptions).orElse(List.of());
    }
  }

  /**
   * Layer names of the source once SRC and DST are given; file names for the first two arguments.
   */
  public static final class SourceAndLayersCompleter
      implements OptionCompleter<CompleterInvocation> {
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void complete(CompleterInvocation in) {
      ConvertCommand c = (ConvertCommand) in.getCommand();
      String given = in.getGivenCompleteValue() == null ? "" : in.getGivenCompleteValue();
      if (c.args == null || c.args.size() < 2) {
        new org.aesh.command.impl.completer.FileOptionCompleter().complete(in);
        return;
      }
      for (String name : layerNames(c.args.getFirst())) {
        if (name.regionMatches(true, 0, given, 0, given.length())) in.addCompleterValue(name);
      }
    }
  }

  /** Names of the layers in {@code location}; empty if it cannot be opened. */
  static List<String> layerNames(String location) {
    try (Dataset ds = Jdal.open(location)) {
      return ds.layers().stream().map(l -> l.type().name()).toList();
    } catch (RuntimeException e) {
      return List.of();
    }
  }
}
