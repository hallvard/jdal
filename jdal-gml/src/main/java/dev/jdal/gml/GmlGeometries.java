package dev.jdal.gml;

import dev.jdal.core.JdalException;
import dev.jdal.geom.CoordinateSequence;
import dev.jdal.geom.Dimension;
import dev.jdal.geom.Geometry;
import dev.jdal.geom.GeometryCollection;
import dev.jdal.geom.LineString;
import dev.jdal.geom.MultiLineString;
import dev.jdal.geom.MultiPoint;
import dev.jdal.geom.MultiPolygon;
import dev.jdal.geom.Point;
import dev.jdal.geom.Polygon;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads GML geometries (GML 2, 3.1 and 3.2, simple features and the linear parts of the complex
 * ones) from a StAX reader. Coordinates are returned as written in the document; see {@link
 * SrsNames} for the axis order.
 */
public final class GmlGeometries {

  private GmlGeometries() {}

  /** A geometry and the {@code srsName} that applies to it (its own or an enclosing one). */
  public record Result(Geometry geometry, String srsName) {}

  private static final Set<String> GEOMETRIES =
      Set.of(
          "Point",
          "LineString",
          "LinearRing",
          "Ring",
          "Polygon",
          "Triangle",
          "Rectangle",
          "Curve",
          "Surface",
          "OrientableCurve",
          "OrientableSurface",
          "CompositeCurve",
          "CompositeSurface",
          "PolyhedralSurface",
          "TriangulatedSurface",
          "Tin",
          "MultiPoint",
          "MultiLineString",
          "MultiPolygon",
          "MultiCurve",
          "MultiSurface",
          "MultiGeometry",
          "GeometryCollection",
          "Box",
          "Envelope",
          "Solid");

  /** True for the names of GML geometry elements. */
  public static boolean isGeometry(String localName) {
    return GEOMETRIES.contains(localName);
  }

  /**
   * Reads the geometry element the reader is positioned on; afterwards the reader is on its end
   * element.
   */
  public static Result read(XMLStreamReader r, String inheritedSrs) throws XMLStreamException {
    return read(r, inheritedSrs, 0);
  }

  /**
   * As {@link #read(XMLStreamReader, String)}, with the dimension to assume when the geometry has
   * no {@code srsDimension}.
   */
  public static Result read(XMLStreamReader r, String inheritedSrs, int defaultDimension)
      throws XMLStreamException {
    String own = r.getAttributeValue(null, "srsName");
    String srs = own != null ? own : inheritedSrs;
    int dim = intAttr(r, "srsDimension", defaultDimension);
    return new Result(geometry(r, srs, dim), srs);
  }

  private static int intAttr(XMLStreamReader r, String name, int fallback) {
    String v = r.getAttributeValue(null, name);
    try {
      return v == null ? fallback : Integer.parseInt(v.strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  // ------------------------------------------------------------------ navigation

  /**
   * Moves to the next child start element; false when the parent ends (reader on its end element).
   */
  static boolean nextChild(XMLStreamReader r) throws XMLStreamException {
    while (r.hasNext()) {
      int e = r.next();
      if (e == XMLStreamConstants.START_ELEMENT) return true;
      if (e == XMLStreamConstants.END_ELEMENT) return false;
    }
    return false;
  }

  /** Skips the current element including its children. */
  static void skip(XMLStreamReader r) throws XMLStreamException {
    int depth = 1;
    while (depth > 0 && r.hasNext()) {
      int e = r.next();
      if (e == XMLStreamConstants.START_ELEMENT) depth++;
      else if (e == XMLStreamConstants.END_ELEMENT) depth--;
    }
  }

  // ------------------------------------------------------------------ geometries

  private static Geometry geometry(XMLStreamReader r, String srs, int dim)
      throws XMLStreamException {
    String name = r.getLocalName();
    switch (name) {
      case "Point":
        {
          Coords c = coordinates(r, dim);
          if (c.size() == 0) return Point.empty(Dimension.XY);
          return c.point(0);
        }
      case "LineString", "LinearRing":
        return new LineString(coordinates(r, dim).sequence());
      case "Ring":
        return ring(r, srs, dim);
      case "Curve", "OrientableCurve", "CompositeCurve":
        return curve(r, srs, dim);
      case "Polygon", "Triangle", "Rectangle":
        return polygon(r, srs, dim);
      case "Surface",
      "OrientableSurface",
      "CompositeSurface",
      "PolyhedralSurface",
      "TriangulatedSurface",
      "Tin":
        return surface(r, srs, dim);
      case "MultiPoint",
      "MultiLineString",
      "MultiCurve",
      "MultiPolygon",
      "MultiSurface",
      "MultiGeometry",
      "GeometryCollection":
        return multi(r, name, srs, dim);
      case "Box":
        return box(coordinates(r, dim));
      case "Envelope":
        return envelope(r, dim);
      default:
        throw new JdalException("Unsupported GML geometry " + name);
    }
  }

  /** The next geometry child (below a wrapper such as {@code exterior}), or null. */
  private static Geometry childGeometry(XMLStreamReader r, String srs, int dim)
      throws XMLStreamException {
    Geometry found = null;
    while (nextChild(r)) {
      if (found == null && isGeometry(r.getLocalName())) {
        String own = r.getAttributeValue(null, "srsName");
        found = geometry(r, own != null ? own : srs, intAttr(r, "srsDimension", dim));
      } else {
        skip(r);
      }
    }
    return found;
  }

  private static CoordinateSequence ringOf(Geometry g) {
    if (g instanceof LineString l) return l.coordinates();
    throw new JdalException("A polygon ring must be a linear ring");
  }

  private static Geometry ring(XMLStreamReader r, String srs, int dim) throws XMLStreamException {
    CoordinateSequence joined = null;
    while (nextChild(r)) {
      if (r.getLocalName().equals("curveMember")) {
        Geometry g = childGeometry(r, srs, dim);
        if (g instanceof LineString l) joined = join(joined, l.coordinates());
      } else {
        skip(r);
      }
    }
    return new LineString(joined == null ? CoordinateSequence.empty(Dimension.XY) : joined);
  }

  private static Geometry curve(XMLStreamReader r, String srs, int dim) throws XMLStreamException {
    CoordinateSequence joined = null;
    while (nextChild(r)) {
      String n = r.getLocalName();
      if (n.equals("segments") || n.equals("baseCurve") || n.equals("curveMember")) {
        while (nextChild(r)) {
          String s = r.getLocalName();
          if (s.equals("LineStringSegment")) {
            joined = join(joined, coordinates(r, intAttr(r, "srsDimension", dim)).sequence());
          } else if (isGeometry(s)) {
            Geometry g = geometry(r, srs, dim);
            if (g instanceof LineString l) joined = join(joined, l.coordinates());
          } else if (s.equals("Arc")
              || s.equals("ArcString")
              || s.equals("Circle")
              || s.equals("CircleByCenterPoint")) {
            throw new JdalException("Curve segments of type " + s + " are not supported");
          } else {
            skip(r);
          }
        }
      } else {
        skip(r);
      }
    }
    return new LineString(joined == null ? CoordinateSequence.empty(Dimension.XY) : joined);
  }

  /** Appends {@code b} to {@code a}, dropping a repeated joint point. */
  private static CoordinateSequence join(CoordinateSequence a, CoordinateSequence b) {
    if (a == null || a.isEmpty()) return b;
    if (b.isEmpty()) return a;
    Dimension d = Dimension.of(a.dimension().hasZ() || b.dimension().hasZ(), false);
    CoordinateSequence x = a.withDimension(d, 0);
    CoordinateSequence y = b.withDimension(d, 0);
    int skip = x.x(x.size() - 1) == y.x(0) && x.y(x.size() - 1) == y.y(0) ? 1 : 0;
    double[] xa = x.toArray();
    double[] ya = y.toArray();
    int n = d.size();
    double[] out = new double[xa.length + ya.length - skip * n];
    System.arraycopy(xa, 0, out, 0, xa.length);
    System.arraycopy(ya, skip * n, out, xa.length, ya.length - skip * n);
    return CoordinateSequence.of(d, out);
  }

  private static Polygon polygon(XMLStreamReader r, String srs, int dim) throws XMLStreamException {
    CoordinateSequence exterior = null;
    List<CoordinateSequence> holes = new ArrayList<>();
    while (nextChild(r)) {
      String n = r.getLocalName();
      if (n.equals("exterior") || n.equals("outerBoundaryIs")) {
        exterior = ringOf(childGeometry(r, srs, dim));
      } else if (n.equals("interior") || n.equals("innerBoundaryIs")) {
        holes.add(ringOf(childGeometry(r, srs, dim)));
      } else {
        skip(r);
      }
    }
    if (exterior == null) return Polygon.empty(Dimension.XY);
    return Polygon.of(exterior, holes.toArray(new CoordinateSequence[0]));
  }

  /** Surfaces are made of patches or members; a single polygon stays a polygon. */
  private static Geometry surface(XMLStreamReader r, String srs, int dim)
      throws XMLStreamException {
    List<Polygon> polygons = new ArrayList<>();
    collectPolygons(r, srs, dim, polygons);
    if (polygons.isEmpty()) return Polygon.empty(Dimension.XY);
    return polygons.size() == 1 ? polygons.getFirst() : MultiPolygon.of(polygons);
  }

  private static void collectPolygons(XMLStreamReader r, String srs, int dim, List<Polygon> out)
      throws XMLStreamException {
    while (nextChild(r)) {
      String n = r.getLocalName();
      if (n.equals("PolygonPatch")
          || n.equals("Polygon")
          || n.equals("Triangle")
          || n.equals("Rectangle")) {
        out.add(polygon(r, srs, dim));
      } else if (n.equals("patches")
          || n.equals("trianglePatches")
          || n.equals("polygonPatches")
          || n.equals("surfaceMember")
          || n.equals("surfaceMembers")
          || n.equals("baseSurface")
          || n.equals("exterior") && false) {
        collectPolygons(r, srs, dim, out);
      } else if (n.equals("Surface")
          || n.equals("CompositeSurface")
          || n.equals("OrientableSurface")
          || n.equals("TriangulatedSurface")
          || n.equals("Tin")
          || n.equals("PolyhedralSurface")) {
        collectPolygons(r, srs, dim, out);
      } else {
        skip(r);
      }
    }
  }

  private static Geometry multi(XMLStreamReader r, String name, String srs, int dim)
      throws XMLStreamException {
    List<Geometry> parts = new ArrayList<>();
    while (nextChild(r)) {
      String n = r.getLocalName();
      if (n.endsWith("Member") || n.endsWith("Members")) {
        while (nextChild(r)) {
          if (isGeometry(r.getLocalName())) {
            String own = r.getAttributeValue(null, "srsName");
            parts.add(geometry(r, own != null ? own : srs, intAttr(r, "srsDimension", dim)));
          } else {
            skip(r);
          }
        }
      } else {
        skip(r);
      }
    }
    return switch (name) {
      case "MultiPoint" ->
          MultiPoint.of(
              parts.stream().filter(Point.class::isInstance).map(Point.class::cast).toList());
      case "MultiLineString", "MultiCurve" ->
          MultiLineString.of(
              parts.stream()
                  .filter(LineString.class::isInstance)
                  .map(LineString.class::cast)
                  .toList());
      case "MultiPolygon", "MultiSurface" -> MultiPolygon.of(flattenPolygons(parts));
      default -> GeometryCollection.of(parts);
    };
  }

  private static List<Polygon> flattenPolygons(List<Geometry> parts) {
    List<Polygon> out = new ArrayList<>();
    for (Geometry g : parts) {
      if (g instanceof Polygon p) out.add(p);
      else if (g instanceof MultiPolygon m) out.addAll(m.parts());
    }
    return out;
  }

  private static Geometry box(Coords c) {
    if (c.size() < 2) throw new JdalException("A GML Box needs two corners");
    return rectangle(c.x(0), c.y(0), c.x(1), c.y(1));
  }

  private static Geometry envelope(XMLStreamReader r, int dim) throws XMLStreamException {
    double[] lower = null;
    double[] upper = null;
    while (nextChild(r)) {
      String n = r.getLocalName();
      if (n.equals("lowerCorner") || n.equals("upperCorner")) {
        String[] t = r.getElementText().strip().split("\\s+");
        double[] v = new double[t.length];
        for (int i = 0; i < t.length; i++) v[i] = Double.parseDouble(t[i]);
        if (n.equals("lowerCorner")) lower = v;
        else upper = v;
      } else {
        skip(r);
      }
    }
    if (lower == null || upper == null || lower.length < 2 || upper.length < 2) {
      throw new JdalException("A GML Envelope needs lowerCorner and upperCorner");
    }
    return rectangle(lower[0], lower[1], upper[0], upper[1]);
  }

  private static Polygon rectangle(double x1, double y1, double x2, double y2) {
    return Polygon.of(CoordinateSequence.xy(x1, y1, x2, y1, x2, y2, x1, y2, x1, y1));
  }

  // ------------------------------------------------------------------ coordinates

  /** Tuples of coordinates in document order. */
  private static final class Coords {
    private final List<double[]> tuples = new ArrayList<>();
    private boolean z;

    void add(double... t) {
      tuples.add(t);
      if (t.length >= 3) z = true;
    }

    int size() {
      return tuples.size();
    }

    double x(int i) {
      return tuples.get(i)[0];
    }

    double y(int i) {
      return tuples.get(i)[1];
    }

    CoordinateSequence sequence() {
      Dimension d = z ? Dimension.XYZ : Dimension.XY;
      double[] data = new double[tuples.size() * d.size()];
      int k = 0;
      for (double[] t : tuples) {
        data[k++] = t[0];
        data[k++] = t[1];
        if (z) data[k++] = t.length >= 3 ? t[2] : 0;
      }
      return CoordinateSequence.of(d, data);
    }

    Point point(int i) {
      double[] t = tuples.get(i);
      return t.length >= 3 ? Point.xyz(t[0], t[1], t[2]) : Point.xy(t[0], t[1]);
    }
  }

  /**
   * Reads {@code pos}, {@code posList}, {@code coordinates} and {@code coord} children of the
   * current element.
   */
  private static Coords coordinates(XMLStreamReader r, int inheritedDim) throws XMLStreamException {
    Coords c = new Coords();
    int ownDim = intAttr(r, "srsDimension", inheritedDim);
    while (nextChild(r)) {
      String n = r.getLocalName();
      switch (n) {
        case "pos", "lowerCorner", "upperCorner" -> {
          int d = intAttr(r, "srsDimension", ownDim);
          double[] t = numbers(r.getElementText());
          if (t.length >= 2) c.add(d == 2 && t.length > 2 ? java.util.Arrays.copyOf(t, 2) : t);
        }
        case "posList" -> {
          int d = intAttr(r, "srsDimension", ownDim);
          double[] t = numbers(r.getElementText());
          if (d <= 0) d = 2;
          if (t.length % d != 0)
            throw new JdalException(
                "posList with " + t.length + " numbers does not fit " + d + " dimensions");
          for (int i = 0; i + d <= t.length; i += d)
            c.add(java.util.Arrays.copyOfRange(t, i, i + d));
        }
        case "coordinates" -> {
          String cs = r.getAttributeValue(null, "cs");
          String ts = r.getAttributeValue(null, "ts");
          String decimal = r.getAttributeValue(null, "decimal");
          readCoordinatesText(
              r.getElementText(), cs == null ? "," : cs, ts, decimal == null ? "." : decimal, c);
        }
        case "coord" -> {
          double[] t = new double[3];
          int n3 = 0;
          while (nextChild(r)) {
            String a = r.getLocalName();
            double v = Double.parseDouble(r.getElementText().strip());
            if (a.equals("X")) {
              t[0] = v;
              n3 = Math.max(n3, 1);
            } else if (a.equals("Y")) {
              t[1] = v;
              n3 = Math.max(n3, 2);
            } else if (a.equals("Z")) {
              t[2] = v;
              n3 = 3;
            }
          }
          c.add(n3 >= 3 ? t : new double[] {t[0], t[1]});
        }
        default -> skip(r);
      }
    }
    return c;
  }

  private static double[] numbers(String text) {
    String t = text.strip();
    if (t.isEmpty()) return new double[0];
    String[] parts = t.split("\\s+");
    double[] v = new double[parts.length];
    for (int i = 0; i < parts.length; i++) {
      try {
        v[i] = Double.parseDouble(parts[i]);
      } catch (NumberFormatException e) {
        throw new JdalException("Invalid number '" + parts[i] + "' in GML coordinates");
      }
    }
    return v;
  }

  private static void readCoordinatesText(
      String text, String cs, String ts, String decimal, Coords out) {
    String t = text.strip();
    if (t.isEmpty()) return;
    String[] tuples = ts == null ? t.split("\\s+") : t.split(java.util.regex.Pattern.quote(ts));
    for (String tuple : tuples) {
      String s = tuple.strip();
      if (s.isEmpty()) continue;
      String[] comps = s.split(java.util.regex.Pattern.quote(cs));
      double[] v = new double[comps.length];
      for (int i = 0; i < comps.length; i++) {
        String num =
            decimal.equals(".") ? comps[i].strip() : comps[i].strip().replace(decimal, ".");
        try {
          v[i] = Double.parseDouble(num);
        } catch (NumberFormatException e) {
          throw new JdalException("Invalid number '" + comps[i] + "' in GML coordinates");
        }
      }
      if (v.length >= 2) out.add(v);
    }
  }
}
