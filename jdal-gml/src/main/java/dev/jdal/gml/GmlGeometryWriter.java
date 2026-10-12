package dev.jdal.gml;

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

/**
 * Writes geometries as GML elements, for filters in WFS requests. Coordinates are written in the
 * order the CRS named by {@code srsName} defines (see {@link SrsNames}).
 */
public final class GmlGeometryWriter {

  /** The GML version to write, which goes with the WFS version. */
  public enum Version {
    /** GML 2 (WFS 1.0): {@code coordinates} elements. */
    GML2("http://www.opengis.net/gml"),
    /** GML 3.1 (WFS 1.1): {@code pos} and {@code posList}. */
    GML31("http://www.opengis.net/gml"),
    /** GML 3.2 (WFS 2.0): as 3.1 with mandatory {@code gml:id}s. */
    GML32("http://www.opengis.net/gml/3.2");

    private final String namespace;

    Version(String namespace) {
      this.namespace = namespace;
    }

    public String namespace() {
      return namespace;
    }

    public static Version forWfs(String wfsVersion) {
      if (wfsVersion.startsWith("2.")) return GML32;
      return wfsVersion.startsWith("1.0") ? GML2 : GML31;
    }
  }

  private final Version v;
  private final boolean swap;
  private final StringBuilder out = new StringBuilder();
  private int ids;

  private GmlGeometryWriter(Version v, boolean swap) {
    this.v = v;
    this.swap = swap;
  }

  /**
   * The geometry as a GML element declaring the {@code gml} namespace. {@code srsName} may be null.
   */
  public static String write(Geometry g, Version version, String srsName) {
    GmlGeometryWriter w = new GmlGeometryWriter(version, SrsNames.parse(srsName).swapsAxes());
    w.geometry(g, srsName, true);
    return w.out.toString();
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
  }

  private void open(String name, String srsName, boolean root, boolean dimension3) {
    out.append("<gml:").append(name);
    if (root) out.append(" xmlns:gml=\"").append(v.namespace()).append('"');
    if (v == Version.GML32) out.append(" gml:id=\"g").append(++ids).append('"');
    if (srsName != null) out.append(" srsName=\"").append(escape(srsName)).append('"');
    if (dimension3 && v != Version.GML2) out.append(" srsDimension=\"3\"");
    out.append('>');
  }

  private void close(String name) {
    out.append("</gml:").append(name).append('>');
  }

  private void geometry(Geometry g, String srsName, boolean root) {
    boolean z = g.dimension().hasZ();
    switch (g) {
      case Point p -> {
        open("Point", srsName, root, z);
        if (v == Version.GML2) {
          out.append("<gml:coordinates>");
          tuple(p.x(), p.y(), p.z(), z, ",");
          out.append("</gml:coordinates>");
        } else {
          out.append("<gml:pos>");
          tuple(p.x(), p.y(), p.z(), z, " ");
          out.append("</gml:pos>");
        }
        close("Point");
      }
      case LineString l -> {
        open("LineString", srsName, root, z);
        sequence(l.coordinates());
        close("LineString");
      }
      case Polygon p -> polygon(p, srsName, root, z);
      case MultiPoint m -> multi("MultiPoint", "pointMember", m.parts(), srsName, root, z);
      case MultiLineString m ->
          multi(
              v == Version.GML32 ? "MultiCurve" : "MultiLineString",
              v == Version.GML32 ? "curveMember" : "lineStringMember",
              m.parts(),
              srsName,
              root,
              z);
      case MultiPolygon m ->
          multi(
              v == Version.GML32 ? "MultiSurface" : "MultiPolygon",
              v == Version.GML32 ? "surfaceMember" : "polygonMember",
              m.parts(),
              srsName,
              root,
              z);
      case GeometryCollection c ->
          multi("MultiGeometry", "geometryMember", c.parts(), srsName, root, z);
    }
  }

  private void multi(
      String name,
      String member,
      List<? extends Geometry> parts,
      String srsName,
      boolean root,
      boolean z) {
    open(name, srsName, root, z);
    for (Geometry part : parts) {
      out.append("<gml:").append(member).append('>');
      geometry(part, null, false);
      out.append("</gml:").append(member).append('>');
    }
    close(name);
  }

  private void polygon(Polygon p, String srsName, boolean root, boolean z) {
    open("Polygon", srsName, root, z);
    List<CoordinateSequence> rings = p.rings();
    for (int i = 0; i < rings.size(); i++) {
      String wrapper =
          i == 0
              ? (v == Version.GML2 ? "outerBoundaryIs" : "exterior")
              : (v == Version.GML2 ? "innerBoundaryIs" : "interior");
      out.append("<gml:").append(wrapper).append('>');
      open("LinearRing", null, false, false);
      sequence(rings.get(i));
      close("LinearRing");
      out.append("</gml:").append(wrapper).append('>');
    }
    close("Polygon");
  }

  private void sequence(CoordinateSequence s) {
    boolean z = s.dimension().hasZ();
    out.append(v == Version.GML2 ? "<gml:coordinates>" : "<gml:posList>");
    for (int i = 0; i < s.size(); i++) {
      if (i > 0) out.append(' ');
      tuple(s.x(i), s.y(i), z ? s.z(i) : 0, z, v == Version.GML2 ? "," : " ");
    }
    out.append(v == Version.GML2 ? "</gml:coordinates>" : "</gml:posList>");
  }

  private void tuple(double x, double y, double z, boolean hasZ, String sep) {
    out.append(number(swap ? y : x)).append(sep).append(number(swap ? x : y));
    if (hasZ) out.append(sep).append(number(z));
  }

  private static String number(double d) {
    return new java.math.BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
  }
}
