package dev.jdal.gml;

import dev.jdal.core.Capability;
import dev.jdal.core.Dataset;
import dev.jdal.core.Driver;
import dev.jdal.core.JdalException;
import dev.jdal.core.Layer;
import dev.jdal.core.Options;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiPath;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** A GML file; every feature type in it is a layer. */
final class GmlDataset implements Dataset {

  private final GmlDriver driver;
  private final String location;
  private final List<Layer> layers = new ArrayList<>();

  GmlDataset(GmlDriver driver, String location, Options options) {
    this.driver = driver;
    this.location = location;
    Vsi vsi = Vsi.global();
    Map<String, FeatureType> types;
    Map<String, Long> counts;
    String schemaLocation;
    GmlSchema.Inference inference =
        new GmlSchema.Inference(flag(options, "EXPOSE_GML_ID"), flag(options, "EXPOSE_FID"));
    try (InputStream in = vsi.newInputStream(location);
        GmlFeatureReader reader = new GmlFeatureReader(in)) {
      schemaLocation = reader.schemaLocation();
      while (reader.hasNext()) inference.add(reader.next());
    } catch (IOException e) {
      throw new JdalException("Cannot read " + location + ": " + e.getMessage(), e);
    }
    types = inference.build();
    counts = inference.counts();
    if (options.bool("USE_SCHEMA", true)) types = withSchema(vsi, location, schemaLocation, types);
    for (var e : types.entrySet()) {
      layers.add(new GmlLayer(location, e.getKey(), e.getValue(), counts.get(e.getKey())));
    }
  }

  /**
   * GDAL_STYLE options: YES, NO or AUTO; the configuration option of the same name with GML_ in
   * front works too.
   */
  private static boolean flag(Options options, String key) {
    String v = options.get(key, dev.jdal.config.Config.global().get("GML_" + key, "AUTO"));
    return !v.equalsIgnoreCase("NO") && !v.equalsIgnoreCase("FALSE");
  }

  /** Replaces inferred types by those of the application schema, if there is one. */
  private static Map<String, FeatureType> withSchema(
      Vsi vsi, String location, String schemaLocation, Map<String, FeatureType> inferred) {
    String xsd = findSchema(vsi, location, schemaLocation);
    if (xsd == null) return inferred;
    Map<String, FeatureType> declared;
    try (java.io.Reader r = vsi.newReader(xsd)) {
      declared =
          XsdSchema.parse(
              r,
              loc -> {
                String p = VsiPath.sibling(xsd, loc);
                try {
                  return vsi.isFile(p) ? vsi.newReader(p) : null;
                } catch (IOException e) {
                  return null;
                }
              });
    } catch (IOException e) {
      return inferred;
    }
    Map<String, FeatureType> out = new java.util.LinkedHashMap<>();
    inferred.forEach(
        (name, t) ->
            out.put(name, declared.containsKey(name) ? GmlSchema.merge(declared.get(name), t) : t));
    return out;
  }

  private static String findSchema(Vsi vsi, String location, String schemaLocation) {
    String same = VsiPath.withExtension(location, "xsd");
    if (vsi.isFile(same)) return same;
    if (schemaLocation != null) {
      for (String token : schemaLocation.strip().split("\\s+")) {
        if (token.endsWith(".xsd") && !token.startsWith("http:") && !token.startsWith("https:")) {
          String p = token.startsWith("/") ? token : VsiPath.sibling(location, token);
          if (vsi.isFile(p)) return p;
        }
      }
    }
    return null;
  }

  @Override
  public String name() {
    return location;
  }

  @Override
  public Driver driver() {
    return driver;
  }

  @Override
  public List<Layer> layers() {
    return List.copyOf(layers);
  }

  /** The features of one type, read from the file again for every scan. */
  private static final class GmlLayer implements Layer {
    private final String location;
    private final String typeName;
    private final FeatureType type;
    private final long count;

    GmlLayer(String location, String typeName, FeatureType type, long count) {
      this.location = location;
      this.typeName = typeName;
      this.type = type;
      this.count = count;
    }

    @Override
    public String name() {
      return typeName;
    }

    @Override
    public FeatureType type() {
      return type;
    }

    @Override
    public Set<Capability> capabilities() {
      return EnumSet.of(Capability.FAST_FEATURE_COUNT);
    }

    @Override
    public long count(dev.jdal.core.Query query) {
      return query.isUnrestricted() ? count : Layer.super.count(query);
    }

    @Override
    public Stream<Feature> features() {
      InputStream in;
      try {
        in = Vsi.global().newInputStream(location);
      } catch (IOException e) {
        throw new JdalException("Cannot read " + location + ": " + e.getMessage(), e);
      }
      GmlFeatureReader reader = new GmlFeatureReader(in);
      Iterator<Feature> it =
          new Iterator<>() {
            private long fid;
            private Feature next;

            private void advance() {
              while (next == null && reader.hasNext()) {
                GmlFeature f = reader.next();
                if (f.typeName().equals(typeName)) next = GmlSchema.convert(type, f, fid++);
              }
            }

            @Override
            public boolean hasNext() {
              advance();
              return next != null;
            }

            @Override
            public Feature next() {
              advance();
              if (next == null) throw new NoSuchElementException();
              Feature f = next;
              next = null;
              return f;
            }
          };
      return StreamSupport.stream(
              Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL),
              false)
          .onClose(reader::close);
    }
  }
}
