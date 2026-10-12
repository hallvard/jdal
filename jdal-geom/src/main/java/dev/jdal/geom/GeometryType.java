package dev.jdal.geom;

/** Flattened (dimension-less) geometry types. Codes follow ISO WKB. */
public enum GeometryType {
  UNKNOWN(0, "Geometry"),
  POINT(1, "Point"),
  LINESTRING(2, "LineString"),
  POLYGON(3, "Polygon"),
  MULTIPOINT(4, "MultiPoint"),
  MULTILINESTRING(5, "MultiLineString"),
  MULTIPOLYGON(6, "MultiPolygon"),
  GEOMETRYCOLLECTION(7, "GeometryCollection"),
  /** Layers without geometry. */
  NONE(100, "None");

  private final int code;
  private final String wktName;

  GeometryType(int code, String wktName) {
    this.code = code;
    this.wktName = wktName;
  }

  public int code() {
    return code;
  }

  /** Mixed-case name as used in GDAL listings, e.g. {@code MultiPolygon}. */
  public String displayName() {
    return wktName;
  }

  public boolean isCollection() {
    return this == MULTIPOINT
        || this == MULTILINESTRING
        || this == MULTIPOLYGON
        || this == GEOMETRYCOLLECTION;
  }

  public static GeometryType fromCode(int code) {
    for (GeometryType t : values()) {
      if (t.code == code) return t;
    }
    throw new IllegalArgumentException("Unsupported geometry type code " + code);
  }

  /** Case-insensitive lookup by WKT keyword (without Z/M suffix). */
  public static GeometryType fromName(String name) {
    for (GeometryType t : values()) {
      if (t.wktName.equalsIgnoreCase(name) || (t == UNKNOWN && "GEOMETRY".equalsIgnoreCase(name)))
        return t;
    }
    throw new IllegalArgumentException("Unknown geometry type " + name);
  }
}
