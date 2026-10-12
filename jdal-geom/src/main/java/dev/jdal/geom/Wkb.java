package dev.jdal.geom;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Well-known binary reader (ISO and PostGIS EWKB flavours) and ISO writer (little endian). */
public final class Wkb {

  private static final int EWKB_Z = 0x80000000;
  private static final int EWKB_M = 0x40000000;
  private static final int EWKB_SRID = 0x20000000;

  private Wkb() {}

  // ---------------------------------------------------------------- writing

  public static byte[] write(Geometry g) {
    return write(g, ByteOrder.LITTLE_ENDIAN);
  }

  public static byte[] write(Geometry g, ByteOrder order) {
    ByteBuffer buf = ByteBuffer.allocate(size(g)).order(order);
    put(buf, g);
    return buf.array();
  }

  private static int size(Geometry g) {
    int header = 5;
    int cs = g.dimension().size() * 8;
    return switch (g) {
      case Point _ -> header + cs;
      case LineString l -> header + 4 + l.coordinates().size() * cs;
      case Polygon p -> header + 4 + p.rings().stream().mapToInt(r -> 4 + r.size() * cs).sum();
      case MultiPoint m -> header + 4 + m.parts().stream().mapToInt(Wkb::size).sum();
      case MultiLineString m -> header + 4 + m.parts().stream().mapToInt(Wkb::size).sum();
      case MultiPolygon m -> header + 4 + m.parts().stream().mapToInt(Wkb::size).sum();
      case GeometryCollection m -> header + 4 + m.parts().stream().mapToInt(Wkb::size).sum();
    };
  }

  private static void put(ByteBuffer b, Geometry g) {
    b.put((byte) (b.order() == ByteOrder.LITTLE_ENDIAN ? 1 : 0));
    b.putInt(g.isoTypeCode());
    switch (g) {
      case Point p -> {
        b.putDouble(p.x()).putDouble(p.y());
        if (p.dimension().hasZ()) b.putDouble(p.z());
        if (p.dimension().hasM()) b.putDouble(p.m());
      }
      case LineString l -> putSeq(b, l.coordinates());
      case Polygon p -> {
        b.putInt(p.rings().size());
        p.rings().forEach(r -> putSeq(b, r));
      }
      case MultiPoint m -> putParts(b, m.parts());
      case MultiLineString m -> putParts(b, m.parts());
      case MultiPolygon m -> putParts(b, m.parts());
      case GeometryCollection m -> putParts(b, m.parts());
    }
  }

  private static void putParts(ByteBuffer b, List<? extends Geometry> parts) {
    b.putInt(parts.size());
    parts.forEach(p -> put(b, p));
  }

  private static void putSeq(ByteBuffer b, CoordinateSequence cs) {
    b.putInt(cs.size());
    for (double v : cs.toArray()) b.putDouble(v);
  }

  // ---------------------------------------------------------------- reading

  public static Geometry read(byte[] wkb) {
    return read(ByteBuffer.wrap(wkb));
  }

  /** Reads one geometry starting at the buffer's position; leaves the position after it. */
  public static Geometry read(ByteBuffer in) {
    return readGeometry(in, null);
  }

  /**
   * EWKB SRID found in the last-read header, or 0. Only meaningful through {@link #readWithSrid}.
   */
  public record WithSrid(Geometry geometry, int srid) {}

  public static WithSrid readWithSrid(byte[] wkb) {
    int[] srid = {0};
    Geometry g = readGeometry(ByteBuffer.wrap(wkb), srid);
    return new WithSrid(g, srid[0]);
  }

  private static Geometry readGeometry(ByteBuffer in, int[] sridOut) {
    ByteOrder saved = in.order();
    try {
      int bo = in.get();
      in.order(bo == 0 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
      int raw = in.getInt();
      boolean z = (raw & EWKB_Z) != 0;
      boolean m = (raw & EWKB_M) != 0;
      if ((raw & 0x8000) != 0) { // legacy "broken" 2.5D flag written by old OGR versions
        z = true;
        raw &= ~0x8000;
      }
      if ((raw & EWKB_SRID) != 0) {
        int srid = in.getInt();
        if (sridOut != null) sridOut[0] = srid;
      }
      int iso = raw & 0x0FFFFFFF;
      Dimension d =
          Dimension.of(
              z || iso / 1000 == 1 || iso / 1000 == 3, m || iso / 1000 == 2 || iso / 1000 == 3);
      GeometryType type = GeometryType.fromCode(iso % 1000);
      return switch (type) {
        case POINT -> {
          double x = in.getDouble(), y = in.getDouble();
          double zz = d.hasZ() ? in.getDouble() : Double.NaN;
          double mm = d.hasM() ? in.getDouble() : Double.NaN;
          yield new Point(d, x, y, zz, mm);
        }
        case LINESTRING -> new LineString(readSeq(in, d));
        case POLYGON -> {
          int n = in.getInt();
          List<CoordinateSequence> rings = new ArrayList<>(n);
          for (int i = 0; i < n; i++) rings.add(readSeq(in, d));
          yield new Polygon(d, rings);
        }
        case MULTIPOINT -> new MultiPoint(d, readParts(in, Point.class));
        case MULTILINESTRING -> new MultiLineString(d, readParts(in, LineString.class));
        case MULTIPOLYGON -> new MultiPolygon(d, readParts(in, Polygon.class));
        case GEOMETRYCOLLECTION -> new GeometryCollection(d, readParts(in, Geometry.class));
        default -> throw new IllegalArgumentException("Unsupported WKB geometry type " + iso);
      };
    } catch (java.nio.BufferUnderflowException e) {
      throw new IllegalArgumentException("Truncated WKB", e);
    } finally {
      in.order(saved);
    }
  }

  private static <T extends Geometry> List<T> readParts(ByteBuffer in, Class<T> cls) {
    int n = in.getInt();
    if (n < 0 || n > in.remaining())
      throw new IllegalArgumentException("Corrupt WKB part count " + n);
    List<T> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) out.add(cls.cast(readGeometry(in, null)));
    return out;
  }

  private static CoordinateSequence readSeq(ByteBuffer in, Dimension d) {
    int n = in.getInt();
    long bytes = (long) n * d.size() * 8;
    if (n < 0 || bytes > in.remaining())
      throw new IllegalArgumentException("Corrupt WKB coordinate count " + n);
    double[] data = new double[n * d.size()];
    for (int i = 0; i < data.length; i++) data[i] = in.getDouble();
    return CoordinateSequence.wrap(d, data);
  }
}
