package dev.jdal.sql;

/** Operators and functions. The aggregates must stay contiguous. */
enum SqlOp {
  OR("OR"),
  AND("AND"),
  NOT("NOT"),
  EQ("="),
  NE("<>"),
  GE(">="),
  LE("<="),
  LT("<"),
  GT(">"),
  LIKE("LIKE"),
  ILIKE("ILIKE"),
  ISNULL("IS NULL"),
  IN("IN"),
  BETWEEN("BETWEEN"),
  ADD("+"),
  SUBTRACT("-"),
  MULTIPLY("*"),
  DIVIDE("/"),
  MODULUS("%"),
  CONCAT("CONCAT"),
  SUBSTR("SUBSTR"),
  HSTORE_GET_VALUE("HSTORE_GET_VALUE"),
  AVG("AVG"),
  MIN("MIN"),
  MAX("MAX"),
  COUNT("COUNT"),
  SUM("SUM"),
  STDDEV_POP("STDDEV_POP"),
  STDDEV_SAMP("STDDEV_SAMP"),
  CAST("CAST");

  final String sqlName;

  SqlOp(String sqlName) {
    this.sqlName = sqlName;
  }

  boolean isAggregate() {
    return ordinal() >= AVG.ordinal() && ordinal() <= STDDEV_SAMP.ordinal();
  }

  /**
   * The function with this name (case-insensitive) that can be called as {@code name(args)}, or
   * null.
   */
  static SqlOp function(String name) {
    for (SqlOp op : values()) {
      if (op.sqlName.equalsIgnoreCase(name)
          && (op == CONCAT || op == SUBSTR || op == HSTORE_GET_VALUE || op.isAggregate())) {
        return op;
      }
    }
    return null;
  }
}
