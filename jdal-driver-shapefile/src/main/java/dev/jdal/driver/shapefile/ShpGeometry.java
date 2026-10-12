package dev.jdal.driver.shapefile;

import dev.jdal.core.JdalException;
import dev.jdal.geom.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Encoding and decoding of the geometry of one shapefile record (everything after the 8 byte record
 * header).
 */
final class ShpGeometry {

  static final int NULL = 0,
      POINT = 1,
      ARC = 3,
      POLYGON = 5,
      MULTIPOINT = 8,
      POINTZ = 11,
      ARCZ = 13,
      POLYGONZ = 15,
      MULTIPOINTZ = 18,
      POINTM = 21,
      ARCM = 23,
      POLYGONM = 25,
      MULTIPOINTM = 28,
      MULTIPATCH = 31;

  static final double NO_DATA = -1e40;
  private static final double NO_DATA_LIMIT = -1e38;

  private ShpGeometry() {}

  static boolean hasZ(int t) {
    return t == POINTZ || t == ARCZ || t == POLYGONZ || t == MULTIPOINTZ || t == MULTIPATCH;
  }

  static boolean isMOnly(int t) {
    return t == POINTM || t == ARCM || t == POLYGONM || t == MULTIPOINTM;
  }

  static boolean isPoint(int t) {
    return t == POINT || t == POINTZ || t == POINTM;
  }

  static boolean isMultiPoint(int t) {
    return t == MULTIPOINT || t == MULTIPOINTZ || t == MULTIPOINTM;
  }

  static boolean isArc(int t) {
    return t == ARC || t == ARCZ || t == ARCM;
  }

  static boolean isPolygon(int t) {
    return t == POLYGON || t == POLYGONZ || t == POLYGONM;
  }

  static boolean isValid(int t) {
    return t == NULL
        || isPoint(t)
        || isMultiPoint(t)
        || isArc(t)
        || isPolygon(t)
        || t == MULTIPATCH;
  }

  // ------------------------------------------------------------------ reading

  /** Bounding box of the record, or null for null shapes (cheap: no geometry is built). */
  static Envelope envelope(ByteBuffer c) {
    if (c.limit() < 4) return null;
    int t = c.getInt(0);
    if (t == NULL) return null;
    if (isPoint(t)) {
      if (c.limit() < 20) return null;
      double x = c.getDouble(4), y = c.getDouble(12);
      return new Envelope(x, y, x, y);
    }
    if (c.limit() < 36) return null;
    return new Envelope(c.getDouble(4), c.getDouble(12), c.getDouble(20), c.getDouble(28));
  }

  /**
   * @param promoteToMulti return MultiLineString/MultiPolygon even for single part shapes
   */
  static Geometry read(ByteBuffer c, boolean promoteToMulti, int record) {
    if (c.limit() < 4) throw corrupt(record, "record too short");
    int t = c.getInt(0);
    if (t == NULL) return null;
    try {
      if (isPoint(t)) return readPoint(c, t, record);
      if (isMultiPoint(t)) return readMultiPoint(c, t, record);
      if (isArc(t) || isPolygon(t)) return readParts(c, t, promoteToMulti, record);
    } catch (java.nio.BufferUnderflowException | IndexOutOfBoundsException e) {
      throw corrupt(record, "truncated");
    }
    throw new JdalException("Unsupported shape type " + t + " in record " + record);
  }

  private static JdalException corrupt(int record, String why) {
    return new JdalException("Corrupt shapefile record " + record + ": " + why);
  }

  private static double m(double v) {
    return v < NO_DATA_LIMIT ? Double.NaN : v;
  }

  private static Geometry readPoint(ByteBuffer c, int t, int record) {
    double x = c.getDouble(4), y = c.getDouble(12);
    if (Double.isNaN(x) || Double.isNaN(y)) return Point.empty(Dimension.XY);
    if (t == POINT) return Point.xy(x, y);
    if (t == POINTZ) {
      double z = c.getDouble(20);
      if (c.limit() >= 36) return Point.xyzm(x, y, z, m(c.getDouble(28)));
      return Point.xyz(x, y, z);
    }
    if (c.limit() >= 28) return Point.xym(x, y, m(c.getDouble(20)));
    return Point.xy(x, y);
  }

  private static Geometry readMultiPoint(ByteBuffer c, int t, int record) {
    int n = c.getInt(36);
    if (n < 0 || 40L + 16L * n > c.limit()) throw corrupt(record, "bad point count " + n);
    int pos = 40 + 16 * n;
    double[] z = null, m = null;
    if (t == MULTIPOINTZ && pos + 16 + 8L * n <= c.limit()) {
      z = doubles(c, pos + 16, n);
      pos += 16 + 8 * n;
    }
    if ((t == MULTIPOINTZ || t == MULTIPOINTM) && pos + 16 + 8L * n <= c.limit())
      m = doubles(c, pos + 16, n);
    Dimension d = Dimension.of(z != null, m != null);
    List<Point> pts = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      double x = c.getDouble(40 + 16 * i), y = c.getDouble(48 + 16 * i);
      pts.add(new Point(d, x, y, z == null ? Double.NaN : z[i], m == null ? Double.NaN : m(m[i])));
    }
    return n == 0 ? MultiPoint.empty(Dimension.XY) : new MultiPoint(d, pts);
  }

  private static double[] doubles(ByteBuffer c, int pos, int n) {
    double[] a = new double[n];
    for (int i = 0; i < n; i++) a[i] = c.getDouble(pos + 8 * i);
    return a;
  }

  private static Geometry readParts(ByteBuffer c, int t, boolean promote, int record) {
    int parts = c.getInt(36), n = c.getInt(40);
    if (parts < 0 || n < 0 || 44L + 4L * parts + 16L * n > c.limit())
      throw corrupt(record, "bad part/point count");
    int pos = 44 + 4 * parts;
    int[] start = new int[parts + 1];
    for (int i = 0; i < parts; i++) start[i] = c.getInt(44 + 4 * i);
    start[parts] = n;
    for (int i = 0; i < parts; i++) {
      if (start[i] < 0 || start[i] > start[i + 1]) throw corrupt(record, "bad part index");
    }
    int ptsPos = pos;
    pos += 16 * n;
    double[] z = null, m = null;
    boolean zType = t == ARCZ || t == POLYGONZ;
    if (zType && pos + 16 + 8L * n <= c.limit()) {
      z = doubles(c, pos + 16, n);
      pos += 16 + 8 * n;
    }
    if (t != ARC && t != POLYGON && pos + 16 + 8L * n <= c.limit()) m = doubles(c, pos + 16, n);
    Dimension d = Dimension.of(z != null, m != null);
    List<CoordinateSequence> seqs = new ArrayList<>(parts);
    for (int p = 0; p < parts; p++) {
      int len = start[p + 1] - start[p];
      double[] data = new double[len * d.size()];
      for (int i = 0; i < len; i++) {
        int src = start[p] + i, o = i * d.size();
        data[o] = c.getDouble(ptsPos + 16 * src);
        data[o + 1] = c.getDouble(ptsPos + 16 * src + 8);
        int k = o + 2;
        if (d.hasZ()) data[k++] = z[src];
        if (d.hasM()) data[k] = m(m[src]);
      }
      seqs.add(CoordinateSequence.of(d, data));
    }
    if (isArc(t)) {
      if (parts == 0) return MultiLineString.empty(Dimension.XY);
      if (parts == 1 && !promote) return new LineString(seqs.getFirst());
      List<LineString> ls = seqs.stream().map(LineString::new).toList();
      return new MultiLineString(d, ls);
    }
    if (parts == 0) return MultiPolygon.empty(Dimension.XY);
    List<Polygon> polys = PolygonOrganizer.organize(seqs);
    if (polys.size() == 1 && !promote) return polys.getFirst();
    return new MultiPolygon(d, polys);
  }

  // ------------------------------------------------------------------ writing

  /**
   * Shape type for a geometry when the layer type is not declared; 0 for null or empty geometries.
   */
  static int typeFor(Geometry g) {
    if (g == null || g.isEmpty()) return NULL;
    Dimension d = g.dimension();
    boolean z = d.hasZ(), m = d.hasM() && !z;
    int base =
        switch (g) {
          case Point _ -> POINT;
          case MultiPoint _ -> MULTIPOINT;
          case LineString _, MultiLineString _ -> ARC;
          case Polygon _, MultiPolygon _ -> POLYGON;
          default ->
              throw new JdalException(
                  "Geometry type " + g.type().displayName() + " is not supported by shapefiles");
        };
    return z ? base + 10 : m ? base + 20 : base;
  }

  /** Shape type for a declared layer type and dimension; 0 if the type is unknown. */
  static int typeFor(GeometryType gt, Dimension d) {
    int base =
        switch (gt) {
          case POINT -> POINT;
          case MULTIPOINT -> MULTIPOINT;
          case LINESTRING, MULTILINESTRING -> ARC;
          case POLYGON, MULTIPOLYGON -> POLYGON;
          default -> NULL;
        };
    if (base == NULL) return NULL;
    return d.hasZ() ? base + 10 : d.hasM() ? base + 20 : base;
  }

  /** The content bytes of a record for {@code g} in a file of {@code shapeType}. */
  static byte[] encode(Geometry g, int shapeType, Bounds bounds) {
    if (g == null || g.isEmpty()) return new byte[4];
    boolean zType = hasZ(shapeType);
    boolean mType = isMOnly(shapeType) || (zType && g.dimension().hasM());
    boolean hasM = g.dimension().hasM() ? (zType || isMOnly(shapeType)) : false;
    if (isPoint(shapeType)) {
      if (!(g instanceof Point p)) throw mismatch(g, shapeType);
      ByteBuffer b =
          le(
              4
                  + 16
                  + (zType ? 8 : 0)
                  + (mType && hasM ? 8 : 0)
                  + (isMOnly(shapeType) && !hasM ? 8 : 0));
      b.putInt(shapeType).putDouble(p.x()).putDouble(p.y());
      bounds.add(p.x(), p.y());
      if (zType) {
        b.putDouble(p.dimension().hasZ() ? p.z() : 0.0);
        bounds.addZ(p.dimension().hasZ() ? p.z() : 0.0);
      }
      if (isMOnly(shapeType) || hasM) {
        double mv = hasM ? p.m() : Double.NaN;
        b.putDouble(Double.isNaN(mv) ? NO_DATA : mv);
        bounds.addM(mv);
      }
      return b.array();
    }
    if (isMultiPoint(shapeType)) {
      if (!(g instanceof MultiPoint mp)) throw mismatch(g, shapeType);
      List<Point> pts = mp.parts().stream().filter(p -> !p.isEmpty()).toList();
      int n = pts.size();
      Dimension d = g.dimension();
      ByteBuffer b =
          le(
              40
                  + 16 * n
                  + (zType ? 16 + 8 * n : 0)
                  + (isMOnly(shapeType) || hasM ? 16 + 8 * n : 0));
      b.putInt(shapeType);
      Bounds local = new Bounds();
      for (Point p : pts) local.add(p.x(), p.y());
      bbox(b, local);
      b.putInt(n);
      for (Point p : pts) b.putDouble(p.x()).putDouble(p.y());
      if (zType) {
        Bounds zb = new Bounds();
        for (Point p : pts) zb.addZ(d.hasZ() ? p.z() : 0.0);
        b.putDouble(zb.zmin).putDouble(zb.zmax);
        for (Point p : pts) b.putDouble(d.hasZ() ? p.z() : 0.0);
        local.zmin = zb.zmin;
        local.zmax = zb.zmax;
      }
      if (isMOnly(shapeType) || hasM) {
        Bounds mb = new Bounds();
        for (Point p : pts) mb.addM(hasM ? p.m() : Double.NaN);
        b.putDouble(mb.mmin).putDouble(mb.mmax);
        for (Point p : pts) b.putDouble(hasM && !Double.isNaN(p.m()) ? p.m() : NO_DATA);
        local.mmin = mb.mmin;
        local.mmax = mb.mmax;
      }
      bounds.add(local);
      return b.array();
    }
    // lines and polygons
    List<CoordinateSequence> parts = new ArrayList<>();
    if (isArc(shapeType)) {
      switch (g) {
        case LineString l -> parts.add(l.coordinates());
        case MultiLineString ml ->
            ml.parts().stream().filter(l -> !l.isEmpty()).forEach(l -> parts.add(l.coordinates()));
        default -> throw mismatch(g, shapeType);
      }
    } else {
      List<Polygon> polys =
          switch (g) {
            case Polygon p -> List.of(p);
            case MultiPolygon mp -> mp.parts();
            default -> throw mismatch(g, shapeType);
          };
      for (Polygon p : polys) {
        for (int r = 0; r < p.rings().size(); r++) {
          CoordinateSequence ring = p.rings().get(r);
          if (ring.isEmpty()) continue;
          ring = close(ring);
          boolean clockwise = PolygonOrganizer.signedArea(ring) < 0;
          if (clockwise != (r == 0))
            ring = ring.reversed(); // shells clockwise, holes counter-clockwise
          parts.add(ring);
        }
      }
    }
    int n = parts.stream().mapToInt(CoordinateSequence::size).sum();
    if (parts.isEmpty()) return new byte[4];
    Dimension d = g.dimension();
    boolean writeM = isMOnly(shapeType) || hasM;
    ByteBuffer b =
        le(44 + 4 * parts.size() + 16 * n + (zType ? 16 + 8 * n : 0) + (writeM ? 16 + 8 * n : 0));
    b.putInt(shapeType);
    Bounds local = new Bounds();
    for (CoordinateSequence s : parts) for (int i = 0; i < s.size(); i++) local.add(s.x(i), s.y(i));
    bbox(b, local);
    b.putInt(parts.size()).putInt(n);
    int idx = 0;
    for (CoordinateSequence s : parts) {
      b.putInt(idx);
      idx += s.size();
    }
    for (CoordinateSequence s : parts)
      for (int i = 0; i < s.size(); i++) b.putDouble(s.x(i)).putDouble(s.y(i));
    if (zType) {
      Bounds zb = new Bounds();
      for (CoordinateSequence s : parts)
        for (int i = 0; i < s.size(); i++) zb.addZ(d.hasZ() ? s.z(i) : 0.0);
      b.putDouble(zb.zmin).putDouble(zb.zmax);
      for (CoordinateSequence s : parts)
        for (int i = 0; i < s.size(); i++) b.putDouble(d.hasZ() ? s.z(i) : 0.0);
      local.zmin = zb.zmin;
      local.zmax = zb.zmax;
    }
    if (writeM) {
      Bounds mb = new Bounds();
      for (CoordinateSequence s : parts)
        for (int i = 0; i < s.size(); i++) mb.addM(hasM ? s.m(i) : Double.NaN);
      b.putDouble(mb.mmin).putDouble(mb.mmax);
      for (CoordinateSequence s : parts) {
        for (int i = 0; i < s.size(); i++)
          b.putDouble(hasM && !Double.isNaN(s.m(i)) ? s.m(i) : NO_DATA);
      }
      local.mmin = mb.mmin;
      local.mmax = mb.mmax;
    }
    bounds.add(local);
    return b.array();
  }

  private static CoordinateSequence close(CoordinateSequence ring) {
    if (ring.isClosed() || ring.isEmpty()) return ring;
    double[] a = ring.toArray();
    int s = ring.dimension().size();
    double[] out = java.util.Arrays.copyOf(a, a.length + s);
    System.arraycopy(a, 0, out, a.length, s);
    return CoordinateSequence.of(ring.dimension(), out);
  }

  private static void bbox(ByteBuffer b, Bounds l) {
    b.putDouble(l.xmin).putDouble(l.ymin).putDouble(l.xmax).putDouble(l.ymax);
  }

  private static ByteBuffer le(int n) {
    return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static JdalException mismatch(Geometry g, int shapeType) {
    return new JdalException(
        "Attempt to write "
            + g.type().displayName()
            + " geometry to a shapefile of type "
            + shapeType);
  }

  /** Running extent of a file (X, Y, Z and M). */
  static final class Bounds {
    double xmin = Double.POSITIVE_INFINITY,
        ymin = Double.POSITIVE_INFINITY,
        xmax = Double.NEGATIVE_INFINITY,
        ymax = Double.NEGATIVE_INFINITY;
    double zmin = Double.POSITIVE_INFINITY, zmax = Double.NEGATIVE_INFINITY;
    double mmin = Double.POSITIVE_INFINITY, mmax = Double.NEGATIVE_INFINITY;

    void add(double x, double y) {
      xmin = Math.min(xmin, x);
      ymin = Math.min(ymin, y);
      xmax = Math.max(xmax, x);
      ymax = Math.max(ymax, y);
    }

    void addZ(double z) {
      zmin = Math.min(zmin, z);
      zmax = Math.max(zmax, z);
    }

    void addM(double m) {
      if (Double.isNaN(m) || m < NO_DATA_LIMIT) return;
      mmin = Math.min(mmin, m);
      mmax = Math.max(mmax, m);
    }

    void add(Bounds o) {
      if (o.xmin <= o.xmax) {
        add(o.xmin, o.ymin);
        add(o.xmax, o.ymax);
      }
      if (o.zmin <= o.zmax) {
        addZ(o.zmin);
        addZ(o.zmax);
      }
      if (o.mmin <= o.mmax) {
        addM(o.mmin);
        addM(o.mmax);
      }
    }

    boolean isEmpty() {
      return xmin > xmax;
    }
  }
}
