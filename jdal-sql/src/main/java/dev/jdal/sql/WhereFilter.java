package dev.jdal.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * The attribute filter of a WHERE clause in a form that other query languages can be generated from
 * (CQL2, OGC filter encoding): logical operators, comparisons of properties with literals, LIKE, IS
 * NULL, IN and BETWEEN. Anything else (arithmetic, functions, casts, special fields) is kept as
 * {@link Unsupported} or {@link Operand.Other}, so that a translator can send the parts it
 * understands and leave the rest to be evaluated here.
 */
public sealed interface WhereFilter {

  enum Cmp {
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE
  }

  record And(List<WhereFilter> parts) implements WhereFilter {}

  record Or(List<WhereFilter> parts) implements WhereFilter {}

  record Not(WhereFilter part) implements WhereFilter {}

  record Compare(Cmp op, Operand left, Operand right) implements WhereFilter {}

  /** {@code escape} is null unless the statement gave one; ILIKE is case-insensitive. */
  record Like(Operand value, Operand pattern, String escape, boolean caseInsensitive)
      implements WhereFilter {}

  record IsNull(Operand value) implements WhereFilter {}

  record In(Operand value, List<Operand> items) implements WhereFilter {}

  record Between(Operand value, Operand low, Operand high) implements WhereFilter {}

  /** A condition this model does not cover. */
  record Unsupported() implements WhereFilter {}

  /** An operand of a comparison. */
  sealed interface Operand {

    /** An unqualified column name, as written. */
    record Property(String name) implements Operand {}

    enum Kind {
      STRING,
      INTEGER,
      REAL,
      BOOLEAN,
      NULL
    }

    /** A literal; {@code text} is the value as written (numbers) or the string itself. */
    record Literal(Kind kind, String text) implements Operand {}

    /** An expression that is not a property or a literal. */
    record Other() implements Operand {}
  }

  /** Parses the text of a WHERE clause; syntax errors throw {@link dev.jdal.core.JdalException}. */
  static WhereFilter parse(String where) {
    return of(Parser.parseExpression(where));
  }

  /** The parts joined by AND at the top level (just this filter if it is not an AND). */
  default List<WhereFilter> conjuncts() {
    return this instanceof And a ? a.parts() : List.of(this);
  }

  // ------------------------------------------------------------------ conversion from the syntax
  // tree

  private static WhereFilter of(Ast.Expr e) {
    if (!(e instanceof Ast.Op op)) return new Unsupported();
    List<Ast.Expr> a = op.args();
    switch (op.op()) {
      case AND, OR:
        {
          List<WhereFilter> parts = new ArrayList<>();
          for (Ast.Expr x : a) parts.add(of(x));
          return op.op() == SqlOp.AND ? new And(parts) : new Or(parts);
        }
      case NOT:
        return new Not(of(a.getFirst()));
      case EQ, NE, LT, LE, GT, GE:
        {
          Cmp c =
              switch (op.op()) {
                case EQ -> Cmp.EQ;
                case NE -> Cmp.NE;
                case LT -> Cmp.LT;
                case LE -> Cmp.LE;
                case GT -> Cmp.GT;
                default -> Cmp.GE;
              };
          return new Compare(c, operand(a.get(0)), operand(a.get(1)));
        }
      case LIKE, ILIKE:
        {
          String escape = null;
          if (a.size() > 2) {
            if (operand(a.get(2)) instanceof Operand.Literal l && l.kind() == Operand.Kind.STRING)
              escape = l.text();
            else return new Unsupported();
          }
          return new Like(operand(a.get(0)), operand(a.get(1)), escape, op.op() == SqlOp.ILIKE);
        }
      case ISNULL:
        return new IsNull(operand(a.getFirst()));
      case IN:
        {
          List<Operand> items = new ArrayList<>();
          for (int i = 1; i < a.size(); i++) items.add(operand(a.get(i)));
          return new In(operand(a.getFirst()), items);
        }
      case BETWEEN:
        return new Between(operand(a.get(0)), operand(a.get(1)), operand(a.get(2)));
      default:
        return new Unsupported();
    }
  }

  private static Operand operand(Ast.Expr e) {
    if (e instanceof Ast.Col c) {
      return c.table() == null && !"*".equals(c.name())
          ? new Operand.Property(c.name())
          : new Operand.Other();
    }
    if (e instanceof Ast.Const k) {
      Val v = k.value();
      if (v.isNull()) return new Operand.Literal(Operand.Kind.NULL, "");
      return switch (v.type()) {
        case INTEGER, INTEGER64 -> new Operand.Literal(Operand.Kind.INTEGER, Long.toString(v.i()));
        case FLOAT ->
            new Operand.Literal(
                Operand.Kind.REAL, k.text() != null ? k.text() : Double.toString(v.f()));
        case STRING -> new Operand.Literal(Operand.Kind.STRING, v.s());
        case BOOLEAN -> new Operand.Literal(Operand.Kind.BOOLEAN, v.i() != 0 ? "true" : "false");
        default -> new Operand.Other();
      };
    }
    return new Operand.Other();
  }
}
