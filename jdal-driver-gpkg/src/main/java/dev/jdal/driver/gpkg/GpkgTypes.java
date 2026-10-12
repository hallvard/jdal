package dev.jdal.driver.gpkg;

import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldSubType;
import dev.jdal.feature.FieldType;
import java.util.List;
import java.util.Locale;

/** Mapping between GeoPackage/SQLite declared column types and field definitions. */
final class GpkgTypes {

  private GpkgTypes() {}

  static FieldDefn fieldFor(String name, String declared) {
    String t = declared == null ? "" : declared.trim().toUpperCase(Locale.ROOT);
    int width = 0;
    int paren = t.indexOf('(');
    if (paren > 0) {
      try {
        width = Integer.parseInt(t.substring(paren + 1, t.indexOf(')')).trim());
      } catch (RuntimeException e) {
        width = 0;
      }
      t = t.substring(0, paren).trim();
    }
    FieldDefn f =
        switch (t) {
          case "BOOLEAN" -> FieldDefn.of(name, FieldType.INTEGER, FieldSubType.BOOLEAN);
          case "TINYINT", "SMALLINT" -> FieldDefn.of(name, FieldType.INTEGER, FieldSubType.INT16);
          case "MEDIUMINT" -> FieldDefn.of(name, FieldType.INTEGER);
          case "INT", "INTEGER", "BIGINT", "INT2", "INT8" ->
              FieldDefn.of(name, FieldType.INTEGER64);
          case "FLOAT" -> FieldDefn.of(name, FieldType.REAL, FieldSubType.FLOAT32);
          case "DOUBLE", "REAL", "DOUBLE PRECISION", "NUMERIC", "DECIMAL" ->
              FieldDefn.of(name, FieldType.REAL);
          case "BLOB" -> FieldDefn.of(name, FieldType.BINARY);
          case "DATE" -> FieldDefn.of(name, FieldType.DATE);
          case "DATETIME", "TIMESTAMP" -> FieldDefn.of(name, FieldType.DATETIME);
          case "JSON" -> FieldDefn.of(name, FieldType.STRING, FieldSubType.JSON);
          default -> FieldDefn.of(name, FieldType.STRING);
        };
    return width > 0 ? f.withWidth(width, 0) : f;
  }

  static String sqlType(FieldDefn f) {
    if (f.subType() == FieldSubType.BOOLEAN) return "BOOLEAN";
    if (f.subType() == FieldSubType.JSON) return "JSON";
    if (f.subType() == FieldSubType.INT16) return "SMALLINT";
    if (f.subType() == FieldSubType.FLOAT32) return "FLOAT";
    return switch (f.type()) {
      case INTEGER -> "MEDIUMINT";
      case INTEGER64 -> "INTEGER";
      case REAL -> "REAL";
      case STRING -> f.width() > 0 ? "TEXT(" + f.width() + ")" : "TEXT";
      case BINARY -> "BLOB";
      case DATE -> "DATE";
      case DATETIME -> "DATETIME";
      case TIME, INTEGER_LIST, INTEGER64_LIST, REAL_LIST, STRING_LIST -> "TEXT";
    };
  }

  static String listToJson(List<?> l) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < l.size(); i++) {
      if (i > 0) sb.append(',');
      Object o = l.get(i);
      if (o instanceof Number n) sb.append(n);
      else
        sb.append('"')
            .append(String.valueOf(o).replace("\\", "\\\\").replace("\"", "\\\""))
            .append('"');
    }
    return sb.append(']').toString();
  }
}
