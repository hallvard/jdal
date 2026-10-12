package dev.jdal.utils;

import dev.jdal.core.*;
import dev.jdal.feature.*;
import dev.jdal.geom.Geometry;
import dev.jdal.srs.CoordinateTransformation;
import dev.jdal.srs.SpatialReference;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copies layers between datasets, optionally reprojecting and filtering (the core of {@code
 * ogr2ogr}).
 */
public final class Translate {

  private Translate() {}

  /**
   * Opens {@code source}, writes {@code destination} and returns the number of features written.
   */
  public static long translate(String source, String destination, TranslateOptions options) {
    final String target = dev.jdal.vsi.Vsi.expandHome(destination);
    try (Dataset src = Jdal.open(source, false, options.openOptions())) {
      Driver driver =
          options.driver() != null
              ? Jdal.driver(options.driver())
                  .orElseThrow(() -> new JdalException("Unknown driver " + options.driver()))
              : Jdal.driverForOutput(target)
                  .orElseThrow(() -> new JdalException("Cannot guess output format of " + target));
      if (!driver.canCreate())
        throw new JdalException("Driver " + driver.name() + " cannot create datasets");
      try (Dataset dst = driver.create(target, options.datasetOptions())) {
        return translate(src, dst, options);
      }
    }
  }

  /** Copies the selected layers of {@code src} into new layers of {@code dst}. */
  public static long translate(Dataset src, Dataset dst, TranslateOptions options) {
    List<Layer> layers = new ArrayList<>();
    if (options.layers().isEmpty()) layers.addAll(src.layers());
    else for (String n : options.layers()) layers.add(src.requireLayer(n));
    long total = 0;
    for (Layer l : layers) {
      total += copyLayer(l, dst, options, layers.size() == 1);
    }
    return total;
  }

  private static long copyLayer(Layer source, Dataset dst, TranslateOptions o, boolean single) {
    FeatureType st = source.type();
    List<GeomFieldDefn> geomFields = new ArrayList<>();
    List<CoordinateTransformation> transforms = new ArrayList<>();
    for (GeomFieldDefn g : st.geomFields()) {
      SpatialReference from = o.sourceSrs() != null ? o.sourceSrs() : g.srs();
      SpatialReference to = o.targetSrs() != null ? o.targetSrs() : from;
      if (o.targetSrs() != null && from == null) {
        throw new JdalException(
            "Layer " + source.name() + " has no spatial reference; set a source SRS");
      }
      transforms.add(o.targetSrs() != null ? CoordinateTransformation.between(from, to) : null);
      GeomFieldDefn ng = g.withSrs(to);
      if (o.forceGeometryType() != null) ng = ng.withType(o.forceGeometryType(), g.dimension());
      geomFields.add(ng);
    }
    String name = single && o.newLayerName() != null ? o.newLayerName() : st.name();
    FeatureType dt = new FeatureType(name, st.fields(), geomFields);

    Layer out = dst.createLayer(dt, o.layerOptions());
    long n = 0;
    try (Stream<Feature> s = source.features(o.query())) {
      for (var it = s.iterator(); it.hasNext(); ) {
        Feature f = it.next();
        List<Geometry> gs = new ArrayList<>(f.geometries().size());
        for (int i = 0; i < f.geometries().size(); i++) {
          Geometry g = f.geometry(i);
          CoordinateTransformation t = transforms.get(i);
          gs.add(g == null ? null : t == null ? g : t.transform(g));
        }
        out.insert(new Feature(out.type(), f.fid(), f.values(), gs));
        n++;
      }
    }
    out.sync();
    return n;
  }
}
