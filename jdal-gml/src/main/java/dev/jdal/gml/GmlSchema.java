package dev.jdal.gml;

import dev.jdal.core.JdalException;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldSubType;
import dev.jdal.feature.FieldType;
import dev.jdal.feature.FieldValues;
import dev.jdal.feature.GeomFieldDefn;
import dev.jdal.geom.Dimension;
import dev.jdal.geom.Geometry;
import dev.jdal.geom.GeometryType;
import dev.jdal.srs.SpatialReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schemas of GML feature types, found by looking at the features, and the conversion of features to
 * typed ones.
 */
public final class GmlSchema {

  private GmlSchema() {}

  /** Collects the feature types of a document. */
  public static final class Inference {
    private final boolean exposeGmlId;
    private final boolean exposeFid;
    private final Map<String, TypeInfo> types = new LinkedHashMap<>();

    public Inference(boolean exposeGmlId, boolean exposeFid) {
      this.exposeGmlId = exposeGmlId;
      this.exposeFid = exposeFid;
    }

    public void add(GmlFeature f) {
      TypeInfo t = types.computeIfAbsent(f.typeName(), k -> new TypeInfo());
      t.count++;
      if (f.id() != null && f.idAttribute() != null && t.idField == null)
        t.idField = f.idAttribute();
      Map<String, Integer> seen = new LinkedHashMap<>();
      for (GmlFeature.Property p : f.properties()) {
        t.fields
            .computeIfAbsent(p.name(), k -> new Inferred())
            .add(p.text(), seen.merge(p.name(), 1, Integer::sum) > 1);
      }
      for (GmlFeature.GeometryProperty g : f.geometries()) {
        GeomInfo gi = t.geoms.computeIfAbsent(g.name(), k -> new GeomInfo());
        gi.add(g.geometry());
        if (gi.srsName == null) gi.srsName = g.srsName() != null ? g.srsName() : f.boundedBySrs();
      }
      if (f.geometries().isEmpty() && t.srsFallback == null) t.srsFallback = f.boundedBySrs();
    }

    /** The feature types in order of first appearance. */
    public Map<String, FeatureType> build() {
      Map<String, FeatureType> out = new LinkedHashMap<>();
      types.forEach(
          (name, t) ->
              out.put(
                  name,
                  t.toType(
                      name,
                      t.idField != null && (t.idField.equals("fid") ? exposeFid : exposeGmlId))));
      return out;
    }

    public Map<String, Long> counts() {
      Map<String, Long> out = new LinkedHashMap<>();
      types.forEach((name, t) -> out.put(name, t.count));
      return out;
    }
  }

  private static final class TypeInfo {
    long count;
    String idField;
    String srsFallback;
    final Map<String, Inferred> fields = new LinkedHashMap<>();
    final Map<String, GeomInfo> geoms = new LinkedHashMap<>();

    FeatureType toType(String name, boolean exposeId) {
      List<FieldDefn> defs = new ArrayList<>();
      if (exposeId && idField != null) defs.add(FieldDefn.of(idField, FieldType.STRING));
      fields.forEach((n, i) -> defs.add(i.toField(n)));
      List<GeomFieldDefn> gd = new ArrayList<>();
      boolean single = geoms.size() == 1;
      geoms.forEach(
          (n, g) -> {
            String srsName = g.srsName != null ? g.srsName : srsFallback;
            SpatialReference srs = SrsNames.parse(srsName).srs();
            gd.add(
                new GeomFieldDefn(
                    single ? "" : n,
                    g.type == null ? GeometryType.UNKNOWN : g.type,
                    g.dimension,
                    srs,
                    true));
          });
      return new FeatureType(name, defs, gd);
    }
  }

  private static final class GeomInfo {
    GeometryType type;
    Dimension dimension = Dimension.XY;
    String srsName;
    private boolean first = true;

    void add(Geometry g) {
      GeometryType t = g.type();
      if (first) type = t;
      else if (type != t)
        type =
            XsdSchema.promote(type) == XsdSchema.promote(t)
                ? XsdSchema.promote(t)
                : GeometryType.UNKNOWN;
      first = false;
      dimension = dimension.union(g.dimension());
    }
  }

  /** What the values of a property look like so far. */
  private static final class Inferred {
    private boolean any;
    private boolean ints = true;
    private boolean longs = true;
    private boolean reals = true;
    private boolean bools = true;
    private boolean dates = true;
    private boolean times = true;
    private boolean dateTimes = true;
    private boolean list;
    private int width;

    void add(String text, boolean repeated) {
      if (repeated) list = true;
      if (text.isEmpty()) return;
      any = true;
      width = Math.max(width, text.length());
      if (ints) ints = isInt(text);
      if (longs) longs = isLong(text);
      if (reals) reals = isReal(text);
      if (bools) bools = text.equals("true") || text.equals("false");
      if (dates) dates = text.matches("\\d{4}-\\d{2}-\\d{2}(Z|[+-]\\d{2}:\\d{2})?");
      if (times) times = text.matches("\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?");
      if (dateTimes)
        dateTimes =
            text.matches(
                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?");
    }

    private static boolean isInt(String s) {
      try {
        if (s.startsWith("+") && s.length() > 1) s = s.substring(1);
        Integer.parseInt(s);
        return !s.startsWith("0") || s.equals("0") || s.startsWith("-");
      } catch (NumberFormatException e) {
        return false;
      }
    }

    private static boolean isLong(String s) {
      try {
        Long.parseLong(s.startsWith("+") && s.length() > 1 ? s.substring(1) : s);
        return !s.startsWith("0") || s.equals("0");
      } catch (NumberFormatException e) {
        return false;
      }
    }

    private static boolean isReal(String s) {
      if (!s.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) return false;
      return true;
    }

    FieldDefn toField(String name) {
      if (!any) return FieldDefn.of(name, list ? FieldType.STRING_LIST : FieldType.STRING);
      if (bools && !list) return FieldDefn.of(name, FieldType.INTEGER, FieldSubType.BOOLEAN);
      FieldType t;
      if (ints) t = list ? FieldType.INTEGER_LIST : FieldType.INTEGER;
      else if (longs) t = list ? FieldType.INTEGER64_LIST : FieldType.INTEGER64;
      else if (reals) t = list ? FieldType.REAL_LIST : FieldType.REAL;
      else if (dateTimes && !list) t = FieldType.DATETIME;
      else if (dates && !list) t = FieldType.DATE;
      else if (times && !list) t = FieldType.TIME;
      else t = list ? FieldType.STRING_LIST : FieldType.STRING;
      return FieldDefn.of(name, t);
    }
  }

  /**
   * The type described by a schema, completed with what the data shows: the id field and the
   * spatial reference of the geometries.
   */
  public static FeatureType merge(FeatureType fromSchema, FeatureType inferred) {
    List<FieldDefn> fields = new ArrayList<>();
    for (FieldDefn f : inferred.fields()) {
      boolean fromGml =
          f.name().equals("gml_id")
              || f.name().equals("fid")
              || f.name().equals("description")
              || f.name().equals("identifier")
              || f.name().equals("name");
      if (fromGml && fromSchema.fieldIndex(f.name()).isEmpty()) fields.add(f);
    }
    fields.addAll(fromSchema.fields());
    List<GeomFieldDefn> geoms = new ArrayList<>();
    for (GeomFieldDefn g : fromSchema.geomFields()) {
      GeomFieldDefn seen = null;
      for (GeomFieldDefn i : inferred.geomFields()) if (i.name().equals(g.name())) seen = i;
      if (seen == null && inferred.geomFields().size() == 1)
        seen = inferred.geomFields().getFirst();
      geoms.add(
          seen == null
              ? g
              : new GeomFieldDefn(
                  g.name(),
                  g.type(),
                  g.dimension().union(seen.dimension()),
                  seen.srs(),
                  g.nullable()));
    }
    return new FeatureType(fromSchema.name(), fields, geoms);
  }

  // ------------------------------------------------------------------ conversion

  /** Makes a typed feature; coordinates of CRS with northing first are put in x, y order. */
  public static Feature convert(FeatureType type, GmlFeature f, long fid) {
    Feature.Builder b = Feature.builder(type).fid(fid);
    Map<String, List<String>> values = new LinkedHashMap<>();
    for (GmlFeature.Property p : f.properties())
      values.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(p.text());
    if (f.id() != null && f.idAttribute() != null && type.fieldIndex(f.idAttribute()).isPresent()) {
      b.set(type.requireFieldIndex(f.idAttribute()), f.id());
    }
    values.forEach(
        (name, list) -> {
          var idx = type.fieldIndex(name);
          if (idx.isEmpty()) return;
          FieldDefn d = type.fields().get(idx.getAsInt());
          Object v = value(d, list);
          if (v != null) b.set(idx.getAsInt(), v);
        });
    boolean single = type.geomFields().size() == 1 && type.geomFields().getFirst().name().isEmpty();
    boolean[] have = new boolean[type.geomFields().size()];
    for (GmlFeature.GeometryProperty g : f.geometries()) {
      int gi = -1;
      for (int i = 0; i < type.geomFields().size(); i++) {
        if (single || type.geomFields().get(i).name().equals(g.name())) {
          gi = i;
          break;
        }
      }
      if (gi < 0 || have[gi]) continue;
      have[gi] = true;
      b.geometry(
          gi,
          toDeclared(
              type.geomFields().get(gi).type(),
              orient(g.geometry(), g.srsName() != null ? g.srsName() : f.boundedBySrs())));
    }
    return b.build();
  }

  private static Object value(FieldDefn d, List<String> texts) {
    try {
      if (d.type().name().endsWith("_LIST")) {
        List<Object> out = new ArrayList<>();
        for (String t : texts) if (!t.isEmpty()) out.add(scalar(listElementType(d.type()), t));
        return out.isEmpty() ? null : FieldValues.coerce(d, out);
      }
      String t = texts.getFirst();
      if (t.isEmpty()) return null;
      if (d.subType() == FieldSubType.BOOLEAN) return t.equals("true") || t.equals("1") ? 1 : 0;
      return FieldValues.coerce(d, t);
    } catch (RuntimeException e) {
      if (e instanceof JdalException) throw e;
      return null; // a value that does not fit the declared type is dropped
    }
  }

  private static FieldType listElementType(FieldType t) {
    return switch (t) {
      case INTEGER_LIST -> FieldType.INTEGER;
      case INTEGER64_LIST -> FieldType.INTEGER64;
      case REAL_LIST -> FieldType.REAL;
      default -> FieldType.STRING;
    };
  }

  private static Object scalar(FieldType t, String text) {
    return switch (t) {
      case INTEGER -> Integer.valueOf(text.startsWith("+") ? text.substring(1) : text);
      case INTEGER64 -> Long.valueOf(text.startsWith("+") ? text.substring(1) : text);
      case REAL -> Double.valueOf(text);
      default -> text;
    };
  }

  /** A single geometry in a layer of the multi type becomes a one-part multi geometry. */
  private static Geometry toDeclared(GeometryType declared, Geometry g) {
    if (declared == GeometryType.MULTIPOLYGON && g instanceof dev.jdal.geom.Polygon p)
      return dev.jdal.geom.MultiPolygon.of(List.of(p));
    if (declared == GeometryType.MULTILINESTRING && g instanceof dev.jdal.geom.LineString l)
      return dev.jdal.geom.MultiLineString.of(List.of(l));
    if (declared == GeometryType.MULTIPOINT && g instanceof dev.jdal.geom.Point p)
      return dev.jdal.geom.MultiPoint.of(List.of(p));
    return g;
  }

  /** Puts coordinates in x, y order if the CRS is written north first. */
  public static Geometry orient(Geometry g, String srsName) {
    if (!SrsNames.parse(srsName).swapsAxes()) return g;
    return g.transform(
        c -> {
          double[] o = c.clone();
          o[0] = c[1];
          o[1] = c[0];
          return o;
        });
  }
}
