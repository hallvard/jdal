package dev.jdal.geom;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Well-known text reader and writer (ISO flavour with Z/M/ZM, accepts the common legacy forms). */
public final class Wkt {

  /** Significant digits (GDAL default {@code OGR_WKT_PRECISION=15}). */
  private static final int PRECISION = 15;

  private Wkt() {}

  // ---------------------------------------------------------------- writing

  public static String write(Geometry g) {
    StringBuilder sb = new StringBuilder();
    writeGeometry(sb, g);
    return sb.toString();
  }

  static String formatCoordinates(CoordinateSequence cs) {
    StringBuilder sb = new StringBuilder();
    writeCoords(sb, cs);
    return sb.toString();
  }

  private static void writeGeometry(StringBuilder sb, Geometry g) {
    sb.append(g.type().displayName().toUpperCase(Locale.ROOT));
    switch (g.dimension()) {
      case XYZ -> sb.append(" Z");
      case XYM -> sb.append(" M");
      case XYZM -> sb.append(" ZM");
      case XY -> {}
    }
    if (g.isEmpty()) {
      sb.append(" EMPTY");
      return;
    }
    sb.append(" (");
    writeBody(sb, g);
    sb.append(')');
  }

  private static void writeBody(StringBuilder sb, Geometry g) {
    switch (g) {
      case Point p -> writeOrdinates(sb, p);
      case LineString l -> writeCoords(sb, l.coordinates());
      case Polygon p -> writeRings(sb, p);
      case MultiPoint mp -> {
        boolean first = true;
        for (Point p : mp.parts()) {
          if (!first) sb.append(',');
          first = false;
          if (p.isEmpty()) sb.append("EMPTY");
          else {
            sb.append('(');
            writeOrdinates(sb, p);
            sb.append(')');
          }
        }
      }
      case MultiLineString ml -> {
        boolean first = true;
        for (LineString l : ml.parts()) {
          if (!first) sb.append(',');
          first = false;
          if (l.isEmpty()) sb.append("EMPTY");
          else {
            sb.append('(');
            writeCoords(sb, l.coordinates());
            sb.append(')');
          }
        }
      }
      case MultiPolygon mp -> {
        boolean first = true;
        for (Polygon p : mp.parts()) {
          if (!first) sb.append(',');
          first = false;
          if (p.isEmpty()) sb.append("EMPTY");
          else {
            sb.append('(');
            writeRings(sb, p);
            sb.append(')');
          }
        }
      }
      case GeometryCollection gc -> {
        boolean first = true;
        for (Geometry c : gc.parts()) {
          if (!first) sb.append(',');
          first = false;
          writeGeometry(sb, c);
        }
      }
    }
  }

  private static void writeRings(StringBuilder sb, Polygon p) {
    boolean first = true;
    for (CoordinateSequence r : p.rings()) {
      if (!first) sb.append(',');
      first = false;
      sb.append('(');
      writeCoords(sb, r);
      sb.append(')');
    }
  }

  private static void writeOrdinates(StringBuilder sb, Point p) {
    NumberFormat.appendXY(sb, p.x(), p.y(), PRECISION);
    if (p.dimension().hasZ()) NumberFormat.appendOrdinate(sb.append(' '), p.z(), PRECISION);
    if (p.dimension().hasM()) NumberFormat.appendOrdinate(sb.append(' '), p.m(), PRECISION);
  }

  private static void writeCoords(StringBuilder sb, CoordinateSequence cs) {
    Dimension d = cs.dimension();
    for (int i = 0, n = cs.size(); i < n; i++) {
      if (i > 0) sb.append(',');
      NumberFormat.appendXY(sb, cs.x(i), cs.y(i), PRECISION);
      if (d.hasZ()) NumberFormat.appendOrdinate(sb.append(' '), cs.z(i), PRECISION);
      if (d.hasM()) NumberFormat.appendOrdinate(sb.append(' '), cs.m(i), PRECISION);
    }
  }

  /** A double as C's {@code %.15g} would print it (as ogrinfo does for Real fields). */
  public static String number(double v) {
    if (Double.isNaN(v)) return "nan";
    if (Double.isInfinite(v)) return v > 0 ? "inf" : "-inf";
    return NumberFormat.general(v, PRECISION);
  }

  // ---------------------------------------------------------------- reading

  public static Geometry read(String wkt) {
    Parser p = new Parser(wkt);
    // Like GDAL, text after the first complete geometry is ignored (8.wkt of the test data has a
    // stray ')').
    return p.geometry();
  }

  private static final class Parser {
    final String s;
    int pos;

    /** Dimension of the geometry being parsed: declared, or inferred from the first coordinate. */
    Dimension dim;

    /** True if {@link #dim} came from a Z/M keyword, so every coordinate must match it. */
    boolean declaredDim;

    Parser(String s) {
      this.s = s;
    }

    IllegalArgumentException error(String msg) {
      return new IllegalArgumentException(msg + " at position " + pos + " in WKT: " + s);
    }

    void skipWs() {
      while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    boolean peekIs(char c) {
      skipWs();
      return pos < s.length() && s.charAt(pos) == c;
    }

    void expect(char c) {
      if (!peekIs(c)) throw error("Expected '" + c + "'");
      pos++;
    }

    String word() {
      skipWs();
      int st = pos;
      while (pos < s.length() && Character.isLetter(s.charAt(pos))) pos++;
      return s.substring(st, pos).toUpperCase(Locale.ROOT);
    }

    Geometry geometry() {
      String name = word();
      if (name.isEmpty()) throw error("Expected geometry keyword");
      Dimension declared = null;
      if (name.endsWith("ZM")
          && name.length() > 2
          && isType(name.substring(0, name.length() - 2))) {
        declared = Dimension.XYZM;
        name = name.substring(0, name.length() - 2);
      } else if (name.endsWith("Z") && isType(name.substring(0, name.length() - 1))) {
        declared = Dimension.XYZ;
        name = name.substring(0, name.length() - 1);
      } else if (name.endsWith("M") && isType(name.substring(0, name.length() - 1))) {
        declared = Dimension.XYM;
        name = name.substring(0, name.length() - 1);
      }
      if (declared == null) {
        int save = pos;
        String mod = word();
        switch (mod) {
          case "Z" -> declared = Dimension.XYZ;
          case "M" -> declared = Dimension.XYM;
          case "ZM" -> declared = Dimension.XYZM;
          default -> pos = save;
        }
      }
      GeometryType type;
      try {
        type = GeometryType.fromName(name);
      } catch (IllegalArgumentException e) {
        throw error("Unknown geometry type '" + name + "'");
      }
      Dimension savedDim = dim;
      boolean savedDeclared = declaredDim;
      declaredDim = declared != null;
      dim = declared != null ? declared : inferDimension();
      try {
        return body(type);
      } finally {
        dim = savedDim;
        declaredDim = savedDeclared;
      }
    }

    static boolean isType(String n) {
      try {
        GeometryType.fromName(n);
        return true;
      } catch (IllegalArgumentException e) {
        return false;
      }
    }

    /**
     * Without a Z/M keyword the dimension is the largest number of ordinates found in any
     * coordinate of the geometry (GDAL accepts mixed 2D/3D coordinates and pads with 0).
     */
    Dimension inferDimension() {
      int depth = 0, max = 0, count = 0;
      boolean inNumber = false;
      for (int i = pos; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '(') {
          depth++;
          count = 0;
          inNumber = false;
        } else if (c == ')' || c == ',') {
          max = Math.max(max, count);
          count = 0;
          inNumber = false;
          if (c == ')' && --depth <= 0) break;
        } else if (Character.isWhitespace(c)) {
          inNumber = false;
        } else if (!inNumber && "+-.0123456789nNiI".indexOf(c) >= 0) {
          inNumber = true;
          count++;
        }
      }
      return switch (Math.min(max, 4)) {
        case 3 -> Dimension.XYZ;
        case 4 -> Dimension.XYZM;
        default -> Dimension.XY;
      };
    }

    boolean isEmptyKeyword() {
      skipWs();
      if (s.regionMatches(true, pos, "EMPTY", 0, 5)) {
        pos += 5;
        return true;
      }
      return false;
    }

    Dimension dimOrXY() {
      return dim == null ? Dimension.XY : dim;
    }

    Geometry body(GeometryType type) {
      if (isEmptyKeyword()) return empty(type);
      expect('(');
      Geometry g =
          switch (type) {
            case POINT -> {
              List<double[]> c = coords();
              if (c.size() != 1) throw error("Point must have one coordinate");
              yield point(c.getFirst());
            }
            case LINESTRING -> new LineString(sequence(coords()));
            case POLYGON -> polygonRings();
            case MULTIPOINT -> multiPoint();
            case MULTILINESTRING -> {
              List<LineString> parts = new ArrayList<>();
              do {
                if (isEmptyKeyword())
                  parts.add(new LineString(CoordinateSequence.empty(dimOrXY())));
                else {
                  expect('(');
                  parts.add(new LineString(sequence(coords())));
                  expect(')');
                }
              } while (comma());
              yield MultiLineString.of(dimOrXY(), parts);
            }
            case MULTIPOLYGON -> {
              List<Polygon> parts = new ArrayList<>();
              do {
                if (isEmptyKeyword()) parts.add(Polygon.empty(dimOrXY()));
                else {
                  expect('(');
                  parts.add(polygonRings());
                  expect(')');
                }
              } while (comma());
              yield MultiPolygon.of(dimOrXY(), parts);
            }
            case GEOMETRYCOLLECTION -> {
              List<Geometry> parts = new ArrayList<>();
              do {
                parts.add(geometry());
              } while (comma());
              Dimension d = dim == null ? Dimension.XY : dim;
              for (Geometry c : parts) d = d.union(c.dimension());
              yield GeometryCollection.of(d, parts);
            }
            default -> throw error("Unsupported geometry type " + type);
          };
      expect(')');
      return g;
    }

    Geometry empty(GeometryType type) {
      Dimension d = dimOrXY();
      return switch (type) {
        case POINT -> Point.empty(d);
        case LINESTRING -> new LineString(CoordinateSequence.empty(d));
        case POLYGON -> Polygon.empty(d);
        case MULTIPOINT -> MultiPoint.empty(d);
        case MULTILINESTRING -> MultiLineString.empty(d);
        case MULTIPOLYGON -> MultiPolygon.empty(d);
        case GEOMETRYCOLLECTION -> GeometryCollection.empty(d);
        default -> throw error("Unsupported geometry type " + type);
      };
    }

    boolean comma() {
      if (peekIs(',')) {
        pos++;
        return true;
      }
      return false;
    }

    Polygon polygonRings() {
      List<CoordinateSequence> rings = new ArrayList<>();
      do {
        if (isEmptyKeyword()) continue;
        expect('(');
        rings.add(sequence(coords()));
        expect(')');
      } while (comma());
      return new Polygon(dimOrXY(), rings);
    }

    MultiPoint multiPoint() {
      List<Point> pts = new ArrayList<>();
      do {
        if (isEmptyKeyword()) {
          pts.add(Point.empty(dimOrXY()));
        } else if (peekIs('(')) {
          pos++;
          pts.add(point(coord()));
          expect(')');
        } else {
          pts.add(point(coord())); // legacy MULTIPOINT (1 2, 3 4)
        }
      } while (comma());
      return new MultiPoint(dimOrXY(), pts);
    }

    List<double[]> coords() {
      List<double[]> out = new ArrayList<>();
      do {
        out.add(coord());
      } while (comma());
      return out;
    }

    double[] coord() {
      double[] buf = new double[4];
      int n = 0;
      while (n < 4) {
        skipWs();
        int st = pos;
        while (pos < s.length() && "+-.0123456789eEnaifNAIF".indexOf(s.charAt(pos)) >= 0) pos++;
        if (st == pos) break;
        String tok = s.substring(st, pos);
        try {
          buf[n++] =
              switch (tok.toLowerCase(Locale.ROOT)) {
                case "nan" -> Double.NaN;
                case "inf" -> Double.POSITIVE_INFINITY;
                case "-inf" -> Double.NEGATIVE_INFINITY;
                default -> Double.parseDouble(tok);
              };
        } catch (NumberFormatException e) {
          pos = st;
          throw error("Bad number '" + tok + "'");
        }
      }
      if (n < 2) throw error("Expected at least two ordinates");
      if (n > dim.size() || (n < dim.size() && declaredDim)) {
        throw error("Expected " + dim.size() + " ordinates but found " + n);
      }
      return java.util.Arrays.copyOf(buf, dim.size()); // shorter coordinates are padded with 0
    }

    Point point(double[] c) {
      return new Point(
          dim,
          c[0],
          c[1],
          dim.hasZ() ? c[2] : Double.NaN,
          dim.hasM() ? c[dim.hasZ() ? 3 : 2] : Double.NaN);
    }

    CoordinateSequence sequence(List<double[]> cs) {
      Dimension d = dimOrXY();
      double[] all = new double[cs.size() * d.size()];
      int i = 0;
      for (double[] c : cs) {
        System.arraycopy(c, 0, all, i, d.size());
        i += d.size();
      }
      return CoordinateSequence.wrap(d, all);
    }
  }
}
