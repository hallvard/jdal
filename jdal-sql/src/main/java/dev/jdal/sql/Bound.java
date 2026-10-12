package dev.jdal.sql;

import java.util.List;

/** Expressions after name resolution and type checking. */
sealed interface Bound permits Bound.BConst, Bound.BColumn, Bound.BOp, Bound.BCast {

  SqlType type();

  record BConst(Val value) implements Bound {
    @Override
    public SqlType type() {
      return value.type();
    }
  }

  /** What a column reference points to in the feature of its table. */
  record FieldRef(Kind kind, int index) {
    enum Kind {
      FIELD,
      GEOM,
      FID,
      OGR_GEOMETRY,
      OGR_STYLE,
      OGR_GEOM_WKT,
      OGR_GEOM_AREA
    }
  }

  record BColumn(int table, FieldRef ref, SqlType type, String name) implements Bound {}

  record BOp(SqlOp op, SqlType type, List<Bound> args) implements Bound {}

  /** CAST; {@code type} is the result type, the rest describes the target for the select list. */
  record BCast(
      Bound arg, SqlType type, String typeName, int width, int precision, String geomType, int srid)
      implements Bound {}
}
