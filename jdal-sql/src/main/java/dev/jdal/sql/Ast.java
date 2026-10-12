package dev.jdal.sql;

import java.util.List;
import java.util.Map;

/** Syntax trees produced by the {@link Parser}. */
final class Ast {

  private Ast() {}

  /** An expression. {@link #depth()} follows GDAL's rule: leaves have depth 1, errors at 128. */
  sealed interface Expr permits Const, Col, Op, Cast {
    int depth();
  }

  record Const(Val value, String text) implements Expr {
    @Override
    public int depth() {
      return 1;
    }
  }

  /** A column reference, optionally qualified; {@code name} is "*" in select lists. */
  record Col(String table, String name) implements Expr {
    @Override
    public int depth() {
      return 1;
    }
  }

  record Op(SqlOp op, List<Expr> args) implements Expr {
    @Override
    public int depth() {
      int d = 0;
      for (Expr e : args) d = Math.max(d, e.depth());
      return d + 1;
    }
  }

  /**
   * CAST(arg AS typeName[(width[,precision])]) or CAST(arg AS GEOMETRY[(type[,srid])]).
   *
   * @param width -1 if not given
   * @param precision -1 if not given
   * @param geomType geometry type name or null
   * @param srid 0 if not given
   */
  record Cast(Expr arg, String typeName, int width, int precision, String geomType, int srid)
      implements Expr {
    @Override
    public int depth() {
      return Math.max(arg.depth(), 1) + 1;
    }
  }

  /** A column of the select list. {@code star} columns use a {@link Col} named "*". */
  record SelectColumn(Expr expr, String alias, boolean distinct, boolean hidden) {}

  record TableRef(String dataSource, String name, String alias) {}

  record Join(int secondaryTable, Expr on) {}

  record OrderBy(String table, String field, boolean ascending) {}

  /**
   * A SELECT.
   *
   * @param excludes fields excluded by {@code * EXCLUDE (...)}, by the index of the star column
   * @param limit -1 for none
   * @param union the select after UNION ALL, or null
   */
  record Select(
      boolean distinct,
      List<SelectColumn> columns,
      Map<Integer, List<Col>> excludes,
      List<TableRef> tables,
      List<Join> joins,
      Expr where,
      List<OrderBy> orderBy,
      long limit,
      long offset,
      Select union) {}
}
