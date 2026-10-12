package dev.jdal.driver.geojson;

import dev.jdal.geom.*;
import dev.jdal.json.*;
import java.util.ArrayList;
import java.util.List;

/** Conversion between GeoJSON geometry objects and {@link Geometry}. */
final class GeoJsonGeometry {

  private GeoJsonGeometry() {}

  // -------------------------------------------------------------- reading

  static Geometry read(JsonValue n) {
    String type = n.path("type").asString("");
    JsonValue coords = n.path("coordinates");
    return switch (type) {
      case "Point" -> point(coords);
      case "LineString" -> new LineString(sequence(coords));
      case "Polygon" -> polygon(coords);
      case "MultiPoint" -> {
        List<Point> pts = new ArrayList<>();
        for (JsonValue c : array(coords)) pts.add(point(c));
        yield MultiPoint.of(pts);
      }
      case "MultiLineString" -> {
        List<LineString> ls = new ArrayList<>();
        for (JsonValue c : array(coords)) ls.add(new LineString(sequence(c)));
        yield MultiLineString.of(ls);
      }
      case "MultiPolygon" -> {
        List<Polygon> ps = new ArrayList<>();
        for (JsonValue c : array(coords)) ps.add(polygon(c));
        yield MultiPolygon.of(ps);
      }
      case "GeometryCollection" -> {
        List<Geometry> gs = new ArrayList<>();
        for (JsonValue c : array(n.path("geometries"))) gs.add(read(c));
        yield GeometryCollection.of(Dimension.XY, gs);
      }
      default ->
          throw new IllegalArgumentException("Unsupported GeoJSON geometry type '" + type + "'");
    };
  }

  private static List<JsonValue> array(JsonValue v) {
    return v instanceof JsonArray a ? a.elements() : List.of();
  }

  private static double num(JsonValue v) {
    if (v instanceof JsonNumber n) return n.doubleValue();
    throw new IllegalArgumentException("GeoJSON coordinates must be numbers");
  }

  private static Dimension dimensionOf(List<JsonValue> position) {
    return position.size() >= 4
        ? Dimension.XYZM
        : position.size() == 3 ? Dimension.XYZ : Dimension.XY;
  }

  private static Point point(JsonValue c) {
    List<JsonValue> p = array(c);
    if (p.isEmpty()) return Point.empty(Dimension.XY);
    if (p.size() < 2)
      throw new IllegalArgumentException("A GeoJSON position needs at least 2 numbers");
    return switch (dimensionOf(p)) {
      case XY -> Point.xy(num(p.get(0)), num(p.get(1)));
      case XYZ -> Point.xyz(num(p.get(0)), num(p.get(1)), num(p.get(2)));
      default -> Point.xyzm(num(p.get(0)), num(p.get(1)), num(p.get(2)), num(p.get(3)));
    };
  }

  private static CoordinateSequence sequence(JsonValue positions) {
    List<JsonValue> ps = array(positions);
    if (ps.isEmpty()) return CoordinateSequence.empty(Dimension.XY);
    Dimension d = Dimension.XY;
    for (JsonValue p : ps) {
      List<JsonValue> pa = array(p);
      if (pa.size() < 2)
        throw new IllegalArgumentException("A GeoJSON position needs at least 2 numbers");
      d = d.union(dimensionOf(pa));
    }
    double[] data = new double[ps.size() * d.size()];
    int i = 0;
    for (JsonValue p : ps) {
      List<JsonValue> pa = array(p);
      for (int k = 0; k < d.size(); k++) data[i++] = k < pa.size() ? num(pa.get(k)) : 0.0;
    }
    return CoordinateSequence.of(d, data);
  }

  private static Polygon polygon(JsonValue rings) {
    List<CoordinateSequence> rs = new ArrayList<>();
    Dimension d = Dimension.XY;
    for (JsonValue r : array(rings)) {
      CoordinateSequence s = sequence(r);
      d = d.union(s.dimension());
      rs.add(s);
    }
    final Dimension dim = d;
    return new Polygon(dim, rs.stream().map(s -> s.withDimension(dim, 0.0)).toList());
  }

  // -------------------------------------------------------------- writing

  static JsonObject write(Geometry g) {
    JsonObject.Builder o = JsonObject.builder();
    switch (g) {
      case Point p ->
          o.put("type", "Point")
              .put(
                  "coordinates",
                  p.isEmpty()
                      ? JsonArray.of()
                      : position(p.dimension(), p.x(), p.y(), p.z(), p.m()));
      case LineString l ->
          o.put("type", "LineString").put("coordinates", sequence(l.coordinates()));
      case Polygon p -> o.put("type", "Polygon").put("coordinates", rings(p));
      case MultiPoint m ->
          o.put("type", "MultiPoint")
              .put(
                  "coordinates",
                  new JsonArray(
                      m.parts().stream()
                          .map(p -> (JsonValue) position(p.dimension(), p.x(), p.y(), p.z(), p.m()))
                          .toList()));
      case MultiLineString m ->
          o.put("type", "MultiLineString")
              .put(
                  "coordinates",
                  new JsonArray(
                      m.parts().stream().map(l -> (JsonValue) sequence(l.coordinates())).toList()));
      case MultiPolygon m ->
          o.put("type", "MultiPolygon")
              .put(
                  "coordinates",
                  new JsonArray(m.parts().stream().map(p -> (JsonValue) rings(p)).toList()));
      case GeometryCollection c ->
          o.put("type", "GeometryCollection")
              .put(
                  "geometries",
                  new JsonArray(c.parts().stream().map(p -> (JsonValue) write(p)).toList()));
    }
    return o.build();
  }

  private static JsonArray position(Dimension d, double x, double y, double z, double m) {
    List<JsonValue> a = new ArrayList<>(4);
    a.add(JsonNumber.of(x));
    a.add(JsonNumber.of(y));
    if (d.hasZ()) a.add(JsonNumber.of(z));
    if (d.hasM()) {
      if (!d.hasZ()) a.add(JsonNumber.of(0.0)); // GeoJSON has no M-only positions
      a.add(JsonNumber.of(m));
    }
    return new JsonArray(a);
  }

  private static JsonArray sequence(CoordinateSequence cs) {
    List<JsonValue> a = new ArrayList<>(cs.size());
    for (int i = 0; i < cs.size(); i++)
      a.add(position(cs.dimension(), cs.x(i), cs.y(i), cs.z(i), cs.m(i)));
    return new JsonArray(a);
  }

  private static JsonArray rings(Polygon p) {
    return new JsonArray(p.rings().stream().map(r -> (JsonValue) sequence(r)).toList());
  }
}
