package dev.jdal.utils;

import dev.jdal.core.Dataset;
import dev.jdal.core.Layer;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldSubType;
import dev.jdal.feature.FieldValues;
import dev.jdal.feature.GeomFieldDefn;
import dev.jdal.geom.Envelope;
import dev.jdal.geom.GeometryType;
import java.util.List;
import java.util.stream.Stream;

/** Text description of a dataset in the format of {@code ogrinfo}. */
public final class Info {

  private Info() {}

  public static String describe(Dataset ds, boolean summaryOnly, boolean features) {
    StringBuilder sb = new StringBuilder();
    sb.append("INFO: Open of `")
        .append(ds.name())
        .append("'\n      using driver `")
        .append(ds.driver().name())
        .append("' successful.\n");
    List<Layer> layers = ds.layers();
    if (!features && !summaryOnly) {
      for (int i = 0; i < layers.size(); i++) {
        Layer l = layers.get(i);
        sb.append(i + 1).append(": ").append(l.name());
        GeometryType t = l.type().geometryType();
        if (t != GeometryType.NONE) sb.append(" (").append(geometryName(l.type())).append(')');
        sb.append('\n');
      }
      return sb.toString();
    }
    for (Layer l : layers) {
      sb.append('\n');
      describeLayer(sb, l, summaryOnly);
    }
    return sb.toString();
  }

  public static String describeLayer(Layer l, boolean summaryOnly) {
    StringBuilder sb = new StringBuilder();
    describeLayer(sb, l, summaryOnly);
    return sb.toString();
  }

  private static void describeLayer(StringBuilder sb, Layer l, boolean summaryOnly) {
    FeatureType t = l.type();
    sb.append("Layer name: ").append(l.name()).append('\n');
    if (t.geomFields().size() <= 1) {
      sb.append("Geometry: ")
          .append(t.geomFields().isEmpty() ? "None" : geometryName(t))
          .append('\n');
    } else {
      sb.append("Geometry fields: ").append(t.geomFields().size()).append('\n');
    }
    sb.append("Feature Count: ").append(l.count()).append('\n');
    if (!t.geomFields().isEmpty()) {
      Envelope e = l.extent().orElse(null);
      if (e != null) {
        sb.append(
            String.format(
                java.util.Locale.ROOT,
                "Extent: (%f, %f) - (%f, %f)%n",
                e.minX(),
                e.minY(),
                e.maxX(),
                e.maxY()));
      }
      if (t.srs() != null) sb.append("Layer SRS: ").append(t.srs().definition()).append('\n');
    }
    for (FieldDefn f : t.fields()) {
      sb.append(f.name()).append(": ").append(f.type().displayName());
      if (f.subType() != FieldSubType.NONE)
        sb.append('(').append(f.subType().displayName()).append(')');
      sb.append(" (").append(f.width()).append('.').append(f.precision()).append(')');
      if (!f.nullable()) sb.append(" NOT NULL");
      if (f.defaultValue() != null) sb.append(" DEFAULT ").append(f.defaultValue());
      sb.append('\n');
    }
    if (summaryOnly) return;
    try (Stream<Feature> s = l.features()) {
      s.forEach(f -> sb.append(dump(f)));
    }
  }

  /** One feature in {@code ogrinfo} format ("OGRFeature(layer):fid"). */
  public static String dump(Feature f) {
    StringBuilder sb = new StringBuilder();
    sb.append("OGRFeature(").append(f.type().name()).append("):").append(f.fid()).append('\n');
    List<FieldDefn> fields = f.type().fields();
    for (int i = 0; i < fields.size(); i++) {
      FieldDefn d = fields.get(i);
      sb.append("  ").append(d.name()).append(" (").append(d.type().displayName()).append(") = ");
      sb.append(f.get(i) == null ? "(null)" : FieldValues.format(d, f.get(i))).append('\n');
    }
    List<GeomFieldDefn> gf = f.type().geomFields();
    for (int i = 0; i < gf.size(); i++) {
      if (f.geometry(i) == null) continue;
      sb.append("  ");
      if (gf.size() > 1) sb.append(gf.get(i).name()).append(" = ");
      sb.append(f.geometry(i).toWkt()).append('\n');
    }
    sb.append('\n');
    return sb.toString();
  }

  private static String geometryName(FeatureType t) {
    GeomFieldDefn g = t.geomFields().getFirst();
    String n = g.type().displayName();
    if (g.type() == GeometryType.UNKNOWN) n = "Unknown (any)";
    return switch (g.dimension()) {
      case XY -> n;
      case XYZ -> "3D " + n;
      case XYM -> "Measured " + n;
      case XYZM -> "3D Measured " + n;
    };
  }
}
