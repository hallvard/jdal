package dev.jdal.sql;

import dev.jdal.core.Diagnostics;
import dev.jdal.core.JdalException;
import dev.jdal.sql.Ast.*;
import dev.jdal.sql.Bound.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves names and checks types, porting GDAL's {@code swq_expr_node::Check}, {@code
 * SWQGeneralChecker} and the automatic promotions of constants (integer to float, string to number,
 * string to timestamp).
 */
final class Binder {

  private final FieldList fields;
  private final boolean allowSecondaryTables;
  private final boolean allowMismatchOnFieldComparison;

  Binder(FieldList fields, boolean allowSecondaryTables, boolean allowMismatchOnFieldComparison) {
    this.fields = fields;
    this.allowSecondaryTables = allowSecondaryTables;
    this.allowMismatchOnFieldComparison = allowMismatchOnFieldComparison;
  }

  Bound bind(Expr e) {
    return switch (e) {
      case Const c -> new BConst(c.value());
      case Col c -> column(c);
      case Cast c -> cast(c);
      case Op o -> operation(o);
    };
  }

  private Bound column(Col c) {
    if (c.name().equals("*")) throw new JdalException("'*' is not allowed in this context");
    int idx = fields.identify(c.table(), c.name());
    if (idx < 0) {
      throw new JdalException(
          c.table() != null
              ? "\"" + c.table() + "\".\"" + c.name() + "\" not recognised as an available field."
              : "\"" + c.name() + "\" not recognised as an available field.");
    }
    FieldList.Entry e = fields.entries.get(idx);
    if (!allowSecondaryTables && e.table() != 0) {
      throw new JdalException(
          "Cannot use field '" + c.name() + "' of a secondary table in this context");
    }
    return new BColumn(e.table(), e.ref(), e.type(), e.name());
  }

  // ------------------------------------------------------------------ CAST

  private Bound cast(Cast c) {
    Bound arg = bind(c.arg());
    String name = c.typeName();
    SqlType argType = arg.type();
    SqlType result;
    if (argType == SqlType.GEOMETRY
        && !(name.equalsIgnoreCase("character") || name.equalsIgnoreCase("geometry"))) {
      throw new JdalException("Cannot cast geometry to " + name);
    }
    switch (name.toLowerCase(Locale.ROOT)) {
      case "boolean" -> result = SqlType.BOOLEAN;
      case "character" -> result = SqlType.STRING;
      case "integer", "smallint" -> result = SqlType.INTEGER;
      case "bigint" -> {
        // CAST(fid AS bigint) makes the FID column 64 bit
        if (arg instanceof BColumn bc
            && bc.ref().kind() == FieldRef.Kind.FID
            && bc.type() == SqlType.INTEGER) {
          arg = new BColumn(bc.table(), bc.ref(), SqlType.INTEGER64, bc.name());
        }
        result = SqlType.INTEGER64;
      }
      case "float", "numeric" -> result = SqlType.FLOAT;
      case "timestamp" -> result = SqlType.TIMESTAMP;
      case "date" -> result = SqlType.DATE;
      case "time" -> result = SqlType.TIME;
      case "geometry" -> {
        if (!(argType == SqlType.GEOMETRY || argType == SqlType.STRING)) {
          throw new JdalException("Cannot cast " + argType.displayName() + " to geometry");
        }
        result = SqlType.GEOMETRY;
      }
      default -> throw new JdalException("Unrecognized typename " + name + " in CAST operator.");
    }
    return new BCast(arg, result, name, c.width(), c.precision(), c.geomType(), c.srid());
  }

  // ------------------------------------------------------------------ operators

  private Bound operation(Op o) {
    if (o.op().isAggregate()) {
      throw new JdalException(
          "Column Summary Function '" + o.op().sqlName + "' found in an inappropriate context.");
    }
    List<Bound> args = new ArrayList<>(o.args().size());
    for (Expr a : o.args()) args.add(bind(a));
    SqlOp op = o.op();
    SqlType ret;
    SqlType argType = SqlType.OTHER;
    switch (op) {
      case AND, OR, NOT -> {
        noGeometry(args);
        ret = SqlType.BOOLEAN;
      }
      case EQ, NE, GT, LT, GE, LE, IN, BETWEEN -> {
        noGeometry(args);
        ret = SqlType.BOOLEAN;
        autoConvertStringToNumeric(args);
        autoPromoteInteger(args);
        autoPromoteStringToDateTime(args);
        argType = args.getFirst().type();
      }
      case ISNULL -> ret = SqlType.BOOLEAN;
      case LIKE, ILIKE -> {
        noGeometry(args);
        ret = SqlType.BOOLEAN;
        argType = SqlType.STRING;
      }
      case ADD -> {
        noGeometry(args);
        autoPromoteInteger(args);
        SqlType a = args.get(0).type(), b = args.get(1).type();
        if (a == SqlType.STRING) {
          ret = SqlType.STRING;
          argType = SqlType.STRING;
        } else if (a == SqlType.FLOAT || b == SqlType.FLOAT) {
          ret = SqlType.FLOAT;
          argType = SqlType.FLOAT;
        } else if (a == SqlType.INTEGER64 || b == SqlType.INTEGER64) {
          ret = SqlType.INTEGER64;
          argType = SqlType.INTEGER64;
        } else {
          ret = SqlType.INTEGER;
          argType = SqlType.INTEGER;
        }
      }
      case SUBTRACT, MULTIPLY, DIVIDE, MODULUS -> {
        noGeometry(args);
        autoPromoteInteger(args);
        SqlType a = args.get(0).type(), b = args.get(1).type();
        if (a == SqlType.FLOAT || b == SqlType.FLOAT) {
          ret = SqlType.FLOAT;
          argType = SqlType.FLOAT;
        } else if (a == SqlType.INTEGER64 || b == SqlType.INTEGER64) {
          ret = SqlType.INTEGER64;
          argType = SqlType.INTEGER64;
        } else {
          ret = SqlType.INTEGER;
          argType = SqlType.INTEGER;
        }
      }
      case CONCAT -> {
        noGeometry(args);
        ret = SqlType.STRING;
        argType = SqlType.STRING;
      }
      case SUBSTR -> {
        noGeometry(args);
        ret = SqlType.STRING;
        if (args.size() > 3 || args.size() < 2) {
          throw new JdalException(
              "Expected 2 or 3 arguments to SUBSTR(), but got " + args.size() + ".");
        }
        if (args.get(0).type() != SqlType.STRING
            || args.get(1).type() != SqlType.INTEGER
            || (args.size() > 2 && args.get(2).type() != SqlType.INTEGER)) {
          throw new JdalException(
              "Wrong argument type for SUBSTR(), expected SUBSTR(string,int,int) or"
                  + " SUBSTR(string,int).");
        }
      }
      case HSTORE_GET_VALUE -> {
        noGeometry(args);
        ret = SqlType.STRING;
        if (args.size() != 2) {
          throw new JdalException(
              "Expected 2 arguments to hstore_get_value(), but got " + args.size() + ".");
        }
        if (args.get(0).type() != SqlType.STRING || args.get(1).type() != SqlType.STRING) {
          throw new JdalException(
              "Wrong argument type for hstore_get_value(), expected"
                  + " hstore_get_value(string,string).");
        }
      }
      default -> throw new JdalException("Unsupported operation " + op.sqlName);
    }
    if (argType != SqlType.OTHER) {
      SqlType want = argType.isInteger() || argType == SqlType.BOOLEAN ? SqlType.FLOAT : argType;
      for (int i = 0; i < args.size(); i++) {
        SqlType t = args.get(i).type();
        if (t.isInteger() || t == SqlType.BOOLEAN) t = SqlType.FLOAT;
        if (want != t) {
          if (allowMismatchOnFieldComparison
              && args.size() == 2
              && op == SqlOp.EQ
              && args.get(0) instanceof BColumn
              && args.get(i) instanceof BColumn) {
            if (want == SqlType.FLOAT && t == SqlType.STRING) {
              args.set(i, new BCast(args.get(i), SqlType.FLOAT, "FLOAT", -1, -1, null, 0));
              break;
            }
            if (t == SqlType.FLOAT && want == SqlType.STRING) {
              args.set(0, new BCast(args.get(0), SqlType.FLOAT, "FLOAT", -1, -1, null, 0));
              break;
            }
          }
          throw new JdalException(
              "Type mismatch or improper type of arguments to " + op.sqlName + " operator.");
        }
      }
    }
    return new BOp(op, ret, List.copyOf(args));
  }

  private static void noGeometry(List<Bound> args) {
    for (Bound a : args) {
      if (a.type() == SqlType.GEOMETRY)
        throw new JdalException("Cannot use geometry field in this operation.");
    }
  }

  /** Mixed string and numeric arguments: string constants become numbers. */
  private static void autoConvertStringToNumeric(List<Bound> args) {
    if (args.size() < 2) return;
    SqlType arg = args.getFirst().type();
    for (int i = 1; i < args.size(); i++) {
      SqlType sub = args.get(i).type();
      if ((arg == SqlType.STRING && (sub.isInteger() || sub == SqlType.FLOAT))
          || (arg.isInteger() && sub == SqlType.STRING)) {
        arg = SqlType.FLOAT;
        break;
      }
    }
    for (int i = 0; i < args.size(); i++) {
      if (arg == SqlType.FLOAT
          && args.get(i) instanceof BConst c
          && c.value().type() == SqlType.STRING) {
        Double d = CNum.parseWhole(c.value().s());
        if (d == null) {
          Diagnostics.warn(
              "Conversion failed when converting the string value '"
                  + c.value().s()
                  + "' to data type float.");
          continue;
        }
        args.set(
            i,
            new BConst(
                new Val(SqlType.FLOAT, (long) (double) d, d, null, null, c.value().isNull())));
      }
    }
  }

  /** integer to int64 to float, for constants only; columns are handled when evaluating. */
  private static void autoPromoteInteger(List<Bound> args) {
    if (args.size() < 2) return;
    SqlType arg = args.getFirst().type();
    for (int i = 1; i < args.size(); i++) {
      SqlType sub = args.get(i).type();
      if (arg.isInteger() && sub == SqlType.FLOAT) arg = SqlType.FLOAT;
      else if (arg == SqlType.INTEGER && sub == SqlType.INTEGER64) arg = SqlType.INTEGER64;
    }
    for (int i = 0; i < args.size(); i++) {
      if (!(args.get(i) instanceof BConst c)) continue;
      Val v = c.value();
      if (arg == SqlType.FLOAT && v.type().isInteger()) {
        args.set(
            i, new BConst(new Val(SqlType.FLOAT, v.i(), (double) v.i(), null, null, v.isNull())));
      } else if (arg == SqlType.INTEGER64 && v.type() == SqlType.INTEGER) {
        args.set(i, new BConst(v.withType(SqlType.INTEGER64)));
      }
    }
  }

  /** Strings mixed with dates are compared as timestamps. */
  private static void autoPromoteStringToDateTime(List<Bound> args) {
    if (args.size() < 2) return;
    SqlType arg = args.getFirst().type();
    for (int i = 1; i < args.size(); i++) {
      if (arg == SqlType.STRING && args.get(i).type().isDateLike()) arg = SqlType.TIMESTAMP;
    }
    for (int i = 0; i < args.size(); i++) {
      Bound b = args.get(i);
      if (arg == SqlType.TIMESTAMP
          && (b.type() == SqlType.STRING || b.type() == SqlType.DATE || b.type() == SqlType.TIME)
          && b instanceof BConst c) {
        args.set(i, new BConst(c.value().withType(SqlType.TIMESTAMP)));
      }
    }
  }
}
