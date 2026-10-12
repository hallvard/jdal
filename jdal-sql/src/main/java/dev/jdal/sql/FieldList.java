package dev.jdal.sql;

import dev.jdal.core.Diagnostics;
import dev.jdal.core.Layer;
import dev.jdal.feature.FeatureType;
import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldSubType;
import dev.jdal.sql.Bound.FieldRef;
import java.util.ArrayList;
import java.util.List;

/**
 * The fields visible to an expression: those of the tables of a SELECT (or of one layer for a
 * filter).
 */
final class FieldList {

  static final String[] SPECIAL_NAMES = {
    "FID", "OGR_GEOMETRY", "OGR_STYLE", "OGR_GEOM_WKT", "OGR_GEOM_AREA"
  };
  static final String DEFAULT_GEOMETRY_NAME = "_ogr_geometry_";

  record Table(String dataSource, String name, String alias, Layer layer, FeatureType type) {}

  record Entry(String name, SqlType type, int table, FieldRef ref) {}

  final List<Table> tables = new ArrayList<>();
  final List<Entry> entries = new ArrayList<>();

  /** True for a single layer without table qualifiers (attribute filters). */
  final boolean qualified;

  FieldList(boolean qualified) {
    this.qualified = qualified;
  }

  static SqlType typeOf(FieldDefn d) {
    return switch (d.type()) {
      case INTEGER -> d.subType() == FieldSubType.BOOLEAN ? SqlType.BOOLEAN : SqlType.INTEGER;
      case INTEGER64 -> d.subType() == FieldSubType.BOOLEAN ? SqlType.BOOLEAN : SqlType.INTEGER64;
      case REAL -> SqlType.FLOAT;
      case STRING -> SqlType.STRING;
      case TIME -> SqlType.TIME;
      case DATE -> SqlType.DATE;
      case DATETIME -> SqlType.TIMESTAMP;
      default -> SqlType.OTHER;
    };
  }

  /**
   * Attribute filter scope: fields, then the special fields, then the geometry fields (as
   * OGRFeatureQuery does).
   */
  static FieldList forFilter(FeatureType type) {
    FieldList fl = new FieldList(false);
    for (int i = 0; i < type.fields().size(); i++) {
      SqlType t = typeOf(type.fields().get(i));
      // date typed fields are timestamps in filters
      if (t == SqlType.DATE || t == SqlType.TIME) t = SqlType.TIMESTAMP;
      fl.entries.add(
          new Entry(type.fields().get(i).name(), t, 0, new FieldRef(FieldRef.Kind.FIELD, i)));
    }
    fl.addSpecial(0, SqlType.INTEGER64);
    for (int g = 0; g < type.geomFields().size(); g++) {
      String n = type.geomFields().get(g).name();
      fl.entries.add(
          new Entry(
              n.isEmpty() ? DEFAULT_GEOMETRY_NAME : n,
              SqlType.GEOMETRY,
              0,
              new FieldRef(FieldRef.Kind.GEOM, g)));
    }
    return fl;
  }

  void addSpecial(int table, SqlType fidType) {
    FieldRef.Kind[] kinds = {
      FieldRef.Kind.FID,
      FieldRef.Kind.OGR_GEOMETRY,
      FieldRef.Kind.OGR_STYLE,
      FieldRef.Kind.OGR_GEOM_WKT,
      FieldRef.Kind.OGR_GEOM_AREA
    };
    SqlType[] types = {fidType, SqlType.STRING, SqlType.STRING, SqlType.STRING, SqlType.FLOAT};
    for (int k = 0; k < kinds.length; k++)
      entries.add(new Entry(SPECIAL_NAMES[k], types[k], table, new FieldRef(kinds[k], 0)));
  }

  /**
   * Position of a field (the first exact-case match, else the first case-insensitive one), or -1.
   */
  int identify(String table, String field) {
    return identify(table, field, true);
  }

  private int identify(String table, String field, boolean oneMoreTime) {
    String tableName = table == null ? "" : table;
    for (int pass = 0; pass < 2; pass++) {
      for (int i = 0; i < entries.size(); i++) {
        Entry e = entries.get(i);
        if (pass == 0 ? !e.name().equals(field) : !e.name().equalsIgnoreCase(field)) continue;
        if (qualified) {
          if (!tableName.isEmpty() && !tableName.equalsIgnoreCase(tables.get(e.table()).alias()))
            continue;
        } else if (!tableName.isEmpty()) {
          break;
        }
        return i;
      }
    }
    // be nice about quoting errors when there is no ambiguity
    if (oneMoreTime
        && !"TRUE".equalsIgnoreCase(dev.jdal.config.Config.global().get("OGR_SQL_STRICT"))) {
      if (!tableName.isEmpty()) {
        boolean tableExists = false;
        if (qualified) {
          for (Table t : tables) tableExists |= tableName.equalsIgnoreCase(t.alias());
        }
        if (!tableExists) {
          int ret = identify(null, tableName + "." + field, false);
          if (ret >= 0) {
            Diagnostics.warn(
                "Passed field name "
                    + tableName
                    + "."
                    + field
                    + " should have been surrounded by double quotes. Accepted since there is no"
                    + " ambiguity...");
          }
          return ret;
        }
      } else {
        int dot = field.indexOf('.');
        if (dot >= 0 && field.indexOf('.', dot + 1) < 0) {
          int ret = identify(field.substring(0, dot), field.substring(dot + 1), false);
          if (ret >= 0) {
            Diagnostics.warn(
                "Passed field name "
                    + field
                    + " should NOT have been surrounded by double quotes. Accepted since there is"
                    + " no ambiguity...");
          }
          return ret;
        }
      }
    }
    return -1;
  }
}
