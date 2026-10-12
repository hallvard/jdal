package dev.jdal.sql;

import dev.jdal.core.Diagnostics;
import dev.jdal.core.JdalException;
import dev.jdal.geom.*;
import dev.jdal.sql.Bound.*;
import java.util.List;
import java.util.Set;

/**
 * Evaluation of bound expressions, a port of GDAL's {@code SWQGeneralEvaluator} and {@code
 * SWQCastEvaluator}.
 */
final class Eval {

  /** Supplies the value of a column for the row being evaluated. */
  interface Row {
    Val value(BColumn column);
  }

  private static final int INT_MAX = Integer.MAX_VALUE;
  private static final Set<SqlOp> DATE_OPS =
      Set.of(SqlOp.EQ, SqlOp.GT, SqlOp.GE, SqlOp.LT, SqlOp.LE, SqlOp.IN, SqlOp.BETWEEN);

  private Eval() {}

  static Val eval(Bound b, Row row) {
    return switch (b) {
      case BConst c -> c.value();
      case BColumn c -> row.value(c);
      case BCast c -> cast(c, eval(c.arg(), row));
      case BOp o -> {
        Val[] values = new Val[o.args().size()];
        for (int i = 0; i < values.length; i++) values[i] = eval(o.args().get(i), row);
        yield apply(o.op(), o.type(), values);
      }
    };
  }

  /**
   * The truth of a filter result: integer, int64 and boolean values count, other types are false.
   */
  static boolean isTrue(Val v) {
    return (v.type().isInteger() || v.type() == SqlType.BOOLEAN) && v.i() != 0;
  }

  static Val apply(SqlOp op, SqlType rt, Val[] a) {
    if ((op == SqlOp.AND || op == SqlOp.OR) && a.length > 2) {
      Val acc = a[0];
      for (int i = 1; i < a.length; i++) acc = apply(op, rt, new Val[] {acc, a[i]});
      return acc;
    }
    if (COMPARISONS.contains(op)) {
      for (Val v : a)
        if (v.type() == SqlType.OTHER)
          return bool(rt, false); // list fields never compare (SWQ_OTHER)
    }
    SqlType t0 = a[0].type();
    if (t0 == SqlType.FLOAT || (a.length > 1 && a[1].type() == SqlType.FLOAT))
      return floatOp(op, rt, a);
    if (t0.isInteger() || t0 == SqlType.BOOLEAN) return intOp(op, rt, a);
    if (t0 == SqlType.TIMESTAMP && DATE_OPS.contains(op)) return dateOp(op, rt, a);
    return stringOp(op, rt, a);
  }

  private static final java.util.Set<SqlOp> COMPARISONS =
      java.util.EnumSet.of(
          SqlOp.EQ, SqlOp.NE, SqlOp.LT, SqlOp.LE, SqlOp.GT, SqlOp.GE, SqlOp.BETWEEN, SqlOp.IN);

  private static Val nullResult(SqlType rt) {
    return switch (rt) {
      case BOOLEAN -> new Val(SqlType.BOOLEAN, 0, 0, null, null, true);
      case FLOAT -> new Val(SqlType.FLOAT, 0, 0, null, null, true);
      case STRING -> new Val(SqlType.STRING, 0, 0, "", null, true);
      default -> new Val(SqlType.INTEGER, 0, 0, null, null, true);
    };
  }

  private static Val bool(SqlType rt, boolean v) {
    return new Val(rt, v ? 1 : 0, v ? 1 : 0, null, null, false);
  }

  private static Val boolNull(SqlType rt, boolean v) {
    return new Val(rt, v ? 1 : 0, v ? 1 : 0, null, null, true);
  }

  // ------------------------------------------------------------------ floating point

  private static Val floatOp(SqlOp op, SqlType rt, Val[] a) {
    double[] d = new double[a.length];
    for (int i = 0; i < a.length; i++) d[i] = a[i].asDouble();
    if (op != SqlOp.ISNULL && op != SqlOp.IN) {
      for (Val v : a) {
        if (v.isNull()) {
          if (rt == SqlType.BOOLEAN || rt == SqlType.FLOAT || rt.isInteger()) return nullResult(rt);
        }
      }
    }
    return switch (op) {
      case EQ -> bool(rt, d[0] == d[1]);
      case NE -> bool(rt, d[0] != d[1]);
      case GT -> bool(rt, d[0] > d[1]);
      case LT -> bool(rt, d[0] < d[1]);
      case GE -> bool(rt, d[0] >= d[1]);
      case LE -> bool(rt, d[0] <= d[1]);
      case BETWEEN -> bool(rt, d[0] >= d[1] && d[0] <= d[2]);
      case ISNULL -> bool(rt, a[0].isNull());
      case IN -> {
        if (a[0].isNull()) yield boolNull(rt, false);
        boolean nullFound = false;
        for (int i = 1; i < a.length; i++) {
          if (a[i].isNull()) nullFound = true;
          else if (d[0] == d[i]) yield bool(rt, true);
        }
        yield nullFound ? boolNull(rt, false) : bool(rt, false);
      }
      case ADD -> Val.real(d[0] + d[1]);
      case SUBTRACT -> Val.real(d[0] - d[1]);
      case MULTIPLY -> Val.real(d[0] * d[1]);
      case DIVIDE -> Val.real(d[1] == 0 ? INT_MAX : d[0] / d[1]);
      case MODULUS -> Val.real(d[1] == 0 ? INT_MAX : d[0] % d[1]);
      default ->
          throw new JdalException(
              "Operator " + op.sqlName + " is not valid on floating point values");
    };
  }

  // ------------------------------------------------------------------ integers and booleans

  private static Val intOp(SqlOp op, SqlType rt, Val[] a) {
    if (op != SqlOp.ISNULL
        && op != SqlOp.OR
        && op != SqlOp.AND
        && op != SqlOp.NOT
        && op != SqlOp.IN) {
      for (Val v : a) {
        if (v.isNull() && (rt == SqlType.BOOLEAN || rt.isInteger()))
          return new Val(rt, 0, 0, null, null, true);
      }
    }
    long x = a[0].i();
    long y = a.length > 1 ? a[1].i() : 0;
    switch (op) {
      case AND:
        {
          boolean r = x != 0 && y != 0;
          return new Val(rt, r ? 1 : 0, r ? 1 : 0, null, null, a[0].isNull() && a[1].isNull());
        }
      case OR:
        {
          boolean r = x != 0 || y != 0;
          return new Val(rt, r ? 1 : 0, r ? 1 : 0, null, null, a[0].isNull() || a[1].isNull());
        }
      case NOT:
        {
          boolean r = x == 0 && !a[0].isNull();
          return new Val(rt, r ? 1 : 0, r ? 1 : 0, null, null, a[0].isNull());
        }
      case EQ:
        return bool(rt, x == y);
      case NE:
        return bool(rt, x != y);
      case GT:
        return bool(rt, x > y);
      case LT:
        return bool(rt, x < y);
      case GE:
        return bool(rt, x >= y);
      case LE:
        return bool(rt, x <= y);
      case BETWEEN:
        return bool(rt, x >= y && x <= a[2].i());
      case ISNULL:
        return bool(rt, a[0].isNull());
      case IN:
        {
          if (a[0].isNull()) return boolNull(rt, false);
          boolean nullFound = false;
          for (int i = 1; i < a.length; i++) {
            if (a[i].isNull()) nullFound = true;
            else if (x == a[i].i()) return bool(rt, true);
          }
          return nullFound ? boolNull(rt, false) : bool(rt, false);
        }
      case ADD:
      case SUBTRACT:
      case MULTIPLY:
      case DIVIDE:
        {
          try {
            long r =
                switch (op) {
                  case ADD -> Math.addExact(x, y);
                  case SUBTRACT -> Math.subtractExact(x, y);
                  case MULTIPLY -> Math.multiplyExact(x, y);
                  default -> y == 0 ? INT_MAX : Math.divideExact(x, y);
                };
            return Val.ofInt(rt, r);
          } catch (ArithmeticException e) {
            Diagnostics.warn("Int overflow");
            return new Val(rt, 0, 0, null, null, true);
          }
        }
      case MODULUS:
        return Val.ofInt(rt, y == 0 ? INT_MAX : x % y);
      default:
        throw new JdalException("Operator " + op.sqlName + " is not valid on integer values");
    }
  }

  // ------------------------------------------------------------------ date times

  private static double[] date(Val v) {
    double[] d = Text.parseDate(v.s() == null ? "" : v.s());
    if (d == null)
      throw new JdalException(
          "Failed to parse date '" + v.s() + "' evaluating OGR WHERE expression");
    return d;
  }

  private static Val dateOp(SqlOp op, SqlType rt, Val[] a) {
    if (rt == SqlType.BOOLEAN && op != SqlOp.IN) {
      for (Val v : a) if (v.isNull()) return new Val(SqlType.BOOLEAN, 0, 0, null, null, true);
    }
    if (op == SqlOp.IN) {
      if (a[0].isNull()) return boolNull(rt, false);
      double[] first = date(a[0]);
      boolean nullFound = false;
      for (int i = 1; i < a.length; i++) {
        if (a[i].isNull()) nullFound = true;
        else if (Text.compareDate(first, date(a[i])) == 0) return bool(rt, true);
      }
      return nullFound ? boolNull(rt, false) : bool(rt, false);
    }
    double[] d0 = date(a[0]);
    double[] d1 = date(a[1]);
    return switch (op) {
      case GT -> bool(rt, Text.compareDate(d0, d1) > 0);
      case GE -> bool(rt, Text.compareDate(d0, d1) >= 0);
      case LT -> bool(rt, Text.compareDate(d0, d1) < 0);
      case LE -> bool(rt, Text.compareDate(d0, d1) <= 0);
      case EQ -> bool(rt, Text.compareDate(d0, d1) == 0);
      case BETWEEN ->
          bool(rt, Text.compareDate(d0, d1) >= 0 && Text.compareDate(d0, date(a[2])) <= 0);
      default -> throw new JdalException("Operator " + op.sqlName + " is not valid on dates");
    };
  }

  // ------------------------------------------------------------------ strings

  private static String str(Val v) {
    return v.s() == null ? "" : v.s();
  }

  private static Val stringOp(SqlOp op, SqlType rt, Val[] a) {
    if (op != SqlOp.ISNULL && op != SqlOp.IN) {
      for (Val v : a) {
        if (v.isNull()) {
          if (rt == SqlType.BOOLEAN) return new Val(SqlType.BOOLEAN, 0, 0, null, null, true);
          if (rt == SqlType.STRING) return new Val(SqlType.STRING, 0, 0, "", null, true);
        }
      }
    }
    switch (op) {
      case EQ:
        {
          String x = str(a[0]), y = str(a[1]);
          boolean tsX = a[0].type() == SqlType.TIMESTAMP || a[0].type() == SqlType.STRING;
          boolean tsY = a[1].type() == SqlType.TIMESTAMP || a[1].type() == SqlType.STRING;
          if (tsX && tsY && x.length() > 3 && y.length() > 3) {
            // a missing time zone on one side matches an explicit +00 on the other
            if (x.endsWith("+00") && y.charAt(y.length() - 3) == ':') {
              return bool(rt, x.regionMatches(true, 0, y, 0, y.length()));
            }
            if (x.charAt(x.length() - 3) == ':' && y.endsWith("+00")) {
              return bool(rt, y.regionMatches(true, 0, x, 0, x.length()));
            }
          }
          return bool(rt, Text.compareIgnoreCase(x, y) == 0);
        }
      case NE:
        return bool(rt, Text.compareIgnoreCase(str(a[0]), str(a[1])) != 0);
      case GT:
        return bool(rt, Text.compareIgnoreCase(str(a[0]), str(a[1])) > 0);
      case LT:
        return bool(rt, Text.compareIgnoreCase(str(a[0]), str(a[1])) < 0);
      case GE:
        return bool(rt, Text.compareIgnoreCase(str(a[0]), str(a[1])) >= 0);
      case LE:
        return bool(rt, Text.compareIgnoreCase(str(a[0]), str(a[1])) <= 0);
      case IN:
        {
          if (a[0].isNull()) return boolNull(rt, false);
          boolean nullFound = false;
          for (int i = 1; i < a.length; i++) {
            if (a[i].isNull()) nullFound = true;
            else if (Text.compareIgnoreCase(str(a[0]), str(a[i])) == 0) return bool(rt, true);
          }
          return nullFound ? boolNull(rt, false) : bool(rt, false);
        }
      case BETWEEN:
        return bool(
            rt,
            Text.compareIgnoreCase(str(a[0]), str(a[1])) >= 0
                && Text.compareIgnoreCase(str(a[0]), str(a[2])) <= 0);
      case LIKE:
      case ILIKE:
        {
          int esc = a.length == 3 && !str(a[2]).isEmpty() ? str(a[2]).codePointAt(0) : 0;
          boolean insensitive =
              op == SqlOp.ILIKE
                  || "YES"
                      .equalsIgnoreCase(
                          dev.jdal.config.Config.global().get("OGR_SQL_LIKE_AS_ILIKE", "NO"));
          return bool(rt, Text.like(str(a[0]), str(a[1]), esc, insensitive));
        }
      case ISNULL:
        return bool(rt, a[0].isNull());
      case CONCAT:
      case ADD:
        {
          StringBuilder sb = new StringBuilder();
          for (Val v : a) sb.append(str(v));
          return new Val(SqlType.STRING, 0, 0, sb.toString(), null, a[0].isNull());
        }
      case SUBSTR:
        return substr(a);
      case HSTORE_GET_VALUE:
        {
          String r = Text.hstoreValue(str(a[0]), str(a[1]));
          return new Val(SqlType.STRING, 0, 0, r == null ? "" : r, null, r == null);
        }
      default:
        throw new JdalException("Operator " + op.sqlName + " is not valid on strings");
    }
  }

  private static Val substr(Val[] a) {
    int[] cps = str(a[0]).codePoints().toArray();
    int offset =
        a[1].type().isInteger()
            ? (int) a[1].i()
            : a[1].type() == SqlType.FLOAT ? (int) a[1].f() : 0;
    int size =
        a.length < 3
            ? 100000
            : a[2].type().isInteger()
                ? (int) a[2].i()
                : a[2].type() == SqlType.FLOAT ? (int) a[2].f() : 0;
    int len = cps.length;
    if (offset > 0) offset--;
    else if (offset < 0) offset = len + offset >= 0 ? len + offset : 0;
    if (size < 0 || offset > len) {
      offset = 0;
      size = 0;
    } else if (offset + size > len) {
      size = len - offset;
    }
    return new Val(SqlType.STRING, 0, 0, new String(cps, offset, size), null, a[0].isNull());
  }

  // ------------------------------------------------------------------ cast

  static Val cast(BCast c, Val src) {
    switch (c.type()) {
      case INTEGER:
        {
          long v =
              switch (src.type()) {
                case INTEGER, BOOLEAN -> src.i();
                case INTEGER64 -> (int) src.i();
                case FLOAT -> (int) src.f();
                default -> CNum.atoi(str(src));
              };
          return new Val(SqlType.INTEGER, v, v, null, null, src.isNull());
        }
      case INTEGER64:
        {
          long v =
              switch (src.type()) {
                case INTEGER, INTEGER64, BOOLEAN -> src.i();
                case FLOAT -> (long) src.f();
                default -> CNum.atoll(str(src));
              };
          return new Val(SqlType.INTEGER64, v, v, null, null, src.isNull());
        }
      case FLOAT:
        {
          double v =
              switch (src.type()) {
                case INTEGER, INTEGER64, BOOLEAN -> (double) src.i();
                case FLOAT -> src.f();
                default -> CNum.atof(str(src));
              };
          return new Val(SqlType.FLOAT, 0, v, null, null, src.isNull());
        }
      case GEOMETRY:
        {
          if (src.isNull()) return Val.geometry(null);
          if (src.type() == SqlType.GEOMETRY) return src;
          if (src.type() == SqlType.STRING) {
            try {
              return Val.geometry(Wkt.read(str(src)));
            } catch (IllegalArgumentException e) {
              return Val.geometry(null);
            }
          }
          return Val.geometry(null);
        }
      default:
        {
          String s =
              switch (src.type()) {
                case INTEGER, BOOLEAN, INTEGER64 -> Long.toString(src.i());
                case FLOAT -> Wkt.number(src.f());
                case GEOMETRY -> src.g() == null ? "" : src.g().toWkt();
                default -> str(src);
              };
          if (c.width() > 0
              && c.geomType() == null
              && s.codePointCount(0, s.length()) > c.width()) {
            s = s.substring(0, s.offsetByCodePoints(0, c.width()));
          }
          return new Val(SqlType.STRING, 0, 0, s, null, src.isNull());
        }
    }
  }

  // ------------------------------------------------------------------ geometry helpers

  /**
   * Planar area as {@code OGR_G_Area}: polygons minus holes, collections summed, 0 for other types.
   */
  static double area(Geometry g) {
    return switch (g) {
      case Polygon p -> {
        double a = 0;
        for (int i = 0; i < p.rings().size(); i++) {
          double ra = Math.abs(PolygonOrganizer.signedArea(p.rings().get(i)));
          a += i == 0 ? ra : -ra;
        }
        yield a;
      }
      case MultiPolygon m -> m.parts().stream().mapToDouble(Eval::area).sum();
      case GeometryCollection c -> c.parts().stream().mapToDouble(Eval::area).sum();
      default -> 0;
    };
  }

  static List<Bound> args(Bound b) {
    return b instanceof BOp o ? o.args() : List.of();
  }
}
