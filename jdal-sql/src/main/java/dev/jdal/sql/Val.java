package dev.jdal.sql;

import dev.jdal.geom.Geometry;

/**
 * An evaluated value (GDAL's constant {@code swq_expr_node}): the integer, float and string members
 * are all kept, the type says which one counts. SQL null is the {@code isNull} flag, so that a null
 * value still has a type.
 */
record Val(SqlType type, long i, double f, String s, Geometry g, boolean isNull) {

  static Val integer(long v) {
    return new Val(
        v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE ? SqlType.INTEGER : SqlType.INTEGER64,
        v,
        v,
        null,
        null,
        false);
  }

  static Val ofInt(SqlType t, long v) {
    return new Val(t, v, v, null, null, false);
  }

  static Val bool(boolean b) {
    return new Val(SqlType.BOOLEAN, b ? 1 : 0, b ? 1 : 0, null, null, false);
  }

  static Val real(double v) {
    return new Val(SqlType.FLOAT, 0, v, null, null, false);
  }

  /** A string; {@code null} is the NULL literal (typed string, flagged null). */
  static Val string(String v) {
    return new Val(SqlType.STRING, 0, 0, v == null ? "" : v, null, v == null);
  }

  static Val timestamp(String v) {
    return new Val(SqlType.TIMESTAMP, 0, 0, v == null ? "" : v, null, v == null);
  }

  static Val geometry(Geometry g) {
    return new Val(SqlType.GEOMETRY, 0, 0, null, g, g == null);
  }

  Val withNullFlag(boolean n) {
    return new Val(type, i, f, s, g, n);
  }

  Val withType(SqlType t) {
    return new Val(t, i, f, s, g, isNull);
  }

  Val asNull() {
    return new Val(type, i, f, s == null ? null : s, g, true);
  }

  /** The numeric value as a double, whichever member holds it. */
  double asDouble() {
    return type.isInteger() || type == SqlType.BOOLEAN ? (double) i : f;
  }
}
