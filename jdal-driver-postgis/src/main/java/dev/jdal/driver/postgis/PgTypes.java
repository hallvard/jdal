package dev.jdal.driver.postgis;

import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldSubType;
import dev.jdal.feature.FieldType;
import dev.jdal.geom.Dimension;
import dev.jdal.geom.GeometryType;
import java.util.Locale;

/** Mapping between PostgreSQL types and field definitions. */
final class PgTypes {

  private PgTypes() {}

  /**
   * @param udt PostgreSQL type name as in information_schema ({@code int4}, {@code _int4}, {@code
   *     varchar}, ...)
   */
  static FieldDefn fieldFor(
      String name, String udt, int charWidth, int numericPrecision, int numericScale) {
    String t = udt.toLowerCase(Locale.ROOT);
    FieldDefn f =
        switch (t) {
          case "int2" -> FieldDefn.of(name, FieldType.INTEGER, FieldSubType.INT16);
          case "int4", "serial", "oid" -> FieldDefn.of(name, FieldType.INTEGER);
          case "int8" -> FieldDefn.of(name, FieldType.INTEGER64);
          case "bool" -> FieldDefn.of(name, FieldType.INTEGER, FieldSubType.BOOLEAN);
          case "float4" -> FieldDefn.of(name, FieldType.REAL, FieldSubType.FLOAT32);
          case "float8", "money" -> FieldDefn.of(name, FieldType.REAL);
          case "numeric" ->
              FieldDefn.of(name, FieldType.REAL)
                  .withWidth(Math.max(numericPrecision, 0), Math.max(numericScale, 0));
          case "bytea" -> FieldDefn.of(name, FieldType.BINARY);
          case "date" -> FieldDefn.of(name, FieldType.DATE);
          case "time", "timetz" -> FieldDefn.of(name, FieldType.TIME);
          case "timestamp", "timestamptz" -> FieldDefn.of(name, FieldType.DATETIME);
          case "json", "jsonb" -> FieldDefn.of(name, FieldType.STRING, FieldSubType.JSON);
          case "uuid" -> FieldDefn.of(name, FieldType.STRING, FieldSubType.UUID);
          case "_int2", "_int4" -> FieldDefn.of(name, FieldType.INTEGER_LIST);
          case "_int8" -> FieldDefn.of(name, FieldType.INTEGER64_LIST);
          case "_float4", "_float8", "_numeric" -> FieldDefn.of(name, FieldType.REAL_LIST);
          case "_text", "_varchar", "_bpchar" -> FieldDefn.of(name, FieldType.STRING_LIST);
          default -> FieldDefn.of(name, FieldType.STRING);
        };
    return charWidth > 0 && f.type() == FieldType.STRING ? f.withWidth(charWidth, 0) : f;
  }

  /** Column type for CREATE TABLE / ALTER TABLE. */
  static String sqlType(FieldDefn f) {
    if (f.subType() == FieldSubType.BOOLEAN && f.type() == FieldType.INTEGER) return "boolean";
    if (f.subType() == FieldSubType.JSON) return "jsonb";
    if (f.subType() == FieldSubType.UUID) return "uuid";
    if (f.subType() == FieldSubType.INT16) return "smallint";
    if (f.subType() == FieldSubType.FLOAT32) return "real";
    return switch (f.type()) {
      case INTEGER -> "integer";
      case INTEGER64 -> "bigint";
      case REAL ->
          f.width() > 0 ? "numeric(" + f.width() + "," + f.precision() + ")" : "double precision";
      case STRING -> f.width() > 0 ? "varchar(" + f.width() + ")" : "text";
      case BINARY -> "bytea";
      case DATE -> "date";
      case TIME -> "time";
      case DATETIME -> "timestamp with time zone";
      case INTEGER_LIST -> "integer[]";
      case INTEGER64_LIST -> "bigint[]";
      case REAL_LIST -> "double precision[]";
      case STRING_LIST -> "text[]";
    };
  }

  /** Element type name for {@code Connection.createArrayOf}. */
  static String arrayElementType(FieldType t) {
    return switch (t) {
      case INTEGER_LIST -> "int4";
      case INTEGER64_LIST -> "int8";
      case REAL_LIST -> "float8";
      default -> "text";
    };
  }

  /** Type clause of {@code geometry(...)}: "Point", "PointZ", "PointZM", "Geometry", ... */
  static String geometryTypeClause(GeometryType type, Dimension d) {
    String base =
        type == GeometryType.UNKNOWN || type == GeometryType.NONE ? "Geometry" : type.displayName();
    return base + (d.hasZ() ? "Z" : "") + (d.hasM() ? "M" : "");
  }

  /**
   * Inverse of {@link #geometryTypeClause}'s base name, as stored in geometry_columns.type
   * ("POINTZM" is possible).
   */
  static GeometryType geometryType(String typeName) {
    String t = typeName.toUpperCase(Locale.ROOT);
    if (t.endsWith("ZM")) t = t.substring(0, t.length() - 2);
    else if (t.endsWith("Z") || t.endsWith("M")) t = t.substring(0, t.length() - 1);
    try {
      return GeometryType.fromName(t);
    } catch (IllegalArgumentException e) {
      return GeometryType.UNKNOWN;
    }
  }

  /**
   * Dimension from geometry_columns: coord_dimension 4 is XYZM; 3 is XYZ, or XYM if the type name
   * ends in M.
   */
  static Dimension dimension(int coordDimension, String typeName) {
    if (coordDimension >= 4) return Dimension.XYZM;
    if (coordDimension == 3)
      return typeName.toUpperCase(Locale.ROOT).endsWith("M") ? Dimension.XYM : Dimension.XYZ;
    return Dimension.XY;
  }
}
