package dev.jdal.ops;

import dev.jdal.geom.CoordinateSequence;
import dev.jdal.geom.Geometry;
import dev.jdal.geom.GeometryCollection;
import dev.jdal.geom.LineString;
import dev.jdal.geom.MultiLineString;
import dev.jdal.geom.MultiPoint;
import dev.jdal.geom.MultiPolygon;
import dev.jdal.geom.Point;
import dev.jdal.geom.Polygon;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

/** Conversion of jdal geometries to JTS geometries. */
public final class Jts {

  private static final GeometryFactory FACTORY = new GeometryFactory();

  private Jts() {}

  public static org.locationtech.jts.geom.Geometry toJts(Geometry g) {
    return switch (g) {
      case Point p ->
          p.isEmpty()
              ? FACTORY.createPoint()
              : FACTORY.createPoint(coordinate(p.x(), p.y(), p.z(), p.dimension().hasZ()));
      case LineString l -> FACTORY.createLineString(coordinates(l.coordinates(), false));
      case Polygon p -> polygon(p);
      case MultiPoint m ->
          FACTORY.createMultiPoint(
              m.parts().stream()
                  .map(p -> (org.locationtech.jts.geom.Point) toJts(p))
                  .toArray(org.locationtech.jts.geom.Point[]::new));
      case MultiLineString m ->
          FACTORY.createMultiLineString(
              m.parts().stream()
                  .map(l -> (org.locationtech.jts.geom.LineString) toJts(l))
                  .toArray(org.locationtech.jts.geom.LineString[]::new));
      case MultiPolygon m ->
          FACTORY.createMultiPolygon(
              m.parts().stream()
                  .map(p -> (org.locationtech.jts.geom.Polygon) toJts(p))
                  .toArray(org.locationtech.jts.geom.Polygon[]::new));
      case GeometryCollection c ->
          FACTORY.createGeometryCollection(
              c.parts().stream()
                  .map(Jts::toJts)
                  .toArray(org.locationtech.jts.geom.Geometry[]::new));
    };
  }

  private static Coordinate coordinate(double x, double y, double z, boolean hasZ) {
    return hasZ ? new Coordinate(x, y, z) : new Coordinate(x, y);
  }

  /** Coordinates of a sequence; {@code close} repeats the first point if a ring is not closed. */
  private static Coordinate[] coordinates(CoordinateSequence s, boolean close) {
    int n = s.size();
    boolean hasZ = s.dimension().hasZ();
    boolean add = close && n > 0 && (s.x(0) != s.x(n - 1) || s.y(0) != s.y(n - 1));
    Coordinate[] out = new Coordinate[n + (add ? 1 : 0)];
    for (int i = 0; i < n; i++) out[i] = coordinate(s.x(i), s.y(i), hasZ ? s.z(i) : 0, hasZ);
    if (add) out[n] = new Coordinate(out[0]);
    return out;
  }

  private static org.locationtech.jts.geom.Polygon polygon(Polygon p) {
    List<CoordinateSequence> rings = p.rings();
    if (rings.isEmpty() || rings.getFirst().isEmpty()) return FACTORY.createPolygon();
    org.locationtech.jts.geom.LinearRing shell =
        FACTORY.createLinearRing(coordinates(rings.getFirst(), true));
    org.locationtech.jts.geom.LinearRing[] holes =
        new org.locationtech.jts.geom.LinearRing[rings.size() - 1];
    for (int i = 1; i < rings.size(); i++)
      holes[i - 1] = FACTORY.createLinearRing(coordinates(rings.get(i), true));
    return FACTORY.createPolygon(shell, holes);
  }
}
