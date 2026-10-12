package dev.jdal.sql;

import dev.jdal.core.JdalException;
import dev.jdal.sql.Ast.*;
import dev.jdal.sql.Lexer.Kind;
import dev.jdal.sql.Lexer.Token;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Recursive descent parser for the OGR SQL SELECT grammar and for WHERE expressions. */
final class Parser {

  private static final int MAX_DEPTH = 128;

  private final String sql;
  private final List<Token> tokens;
  private int pos;
  private int lastValid;

  private Parser(String sql) {
    this.sql = sql;
    this.tokens = Lexer.tokenize(sql);
  }

  /** Parses a complete expression, e.g. an attribute filter. */
  static Expr parseExpression(String text) {
    Parser p = new Parser(text);
    Expr e = p.orExpr();
    p.expectEof();
    return e;
  }

  /** Parses a SELECT statement (with UNION ALL). */
  static Select parseSelect(String text) {
    Parser p = new Parser(text);
    Select s = p.selectStatement();
    p.expectEof();
    return s;
  }

  // ------------------------------------------------------------------ helpers

  private Token peek() {
    return tokens.get(pos);
  }

  private Token peek(int k) {
    return tokens.get(Math.min(pos + k, tokens.size() - 1));
  }

  private Token next() {
    Token t = tokens.get(pos);
    lastValid = t.pos();
    if (t.kind() != Kind.EOF) pos++;
    return t;
  }

  private JdalException error(Token unexpected) {
    return error("syntax error, unexpected " + unexpected.describe(), unexpected.pos());
  }

  private JdalException error(String message, int at) {
    StringBuilder sb =
        new StringBuilder("SQL Expression Parsing Error: ")
            .append(message)
            .append(". Occurred around :\n");
    int n = Math.min(at, sql.length());
    sb.append(sql, Math.max(0, n - 40), Math.min(sql.length(), n + 40)).append('\n');
    sb.append(" ".repeat(Math.min(n, 40))).append('^');
    return new JdalException(sb.toString());
  }

  private void expectEof() {
    if (peek().kind() != Kind.EOF) throw error(peek());
  }

  private void expectSymbol(String s) {
    if (!peek().isSymbol(s)) throw error(peek());
    next();
  }

  private void expectKeyword(String k) {
    if (!peek().isKeyword(k)) throw error(peek());
    next();
  }

  private boolean acceptSymbol(String s) {
    if (peek().isSymbol(s)) {
      next();
      return true;
    }
    return false;
  }

  private boolean acceptKeyword(String k) {
    if (peek().isKeyword(k)) {
      next();
      return true;
    }
    return false;
  }

  private boolean isIdentifier(Token t) {
    return t.kind() == Kind.IDENTIFIER || t.isKeyword("HIDDEN");
  }

  private String identifier() {
    Token t = peek();
    if (!isIdentifier(t)) throw error(t);
    next();
    return t.kind() == Kind.IDENTIFIER
        ? t.text()
        : t.text(); // HIDDEN keeps its upper case spelling as GDAL does
  }

  private <E extends Expr> E checkDepth(E e) {
    if (e.depth() >= MAX_DEPTH) throw error("Maximum expression depth reached", lastValid);
    return e;
  }

  // ------------------------------------------------------------------ select

  private Select selectStatement() {
    boolean paren = false;
    if (peek().isSymbol("(")) {
      paren = true;
      next();
    }
    Select core = selectCore();
    if (paren) expectSymbol(")");
    if (peek().isKeyword("UNION")) {
      next();
      expectKeyword("ALL");
      Select other = selectStatement();
      return new Select(
          core.distinct(),
          core.columns(),
          core.excludes(),
          core.tables(),
          core.joins(),
          core.where(),
          core.orderBy(),
          core.limit(),
          core.offset(),
          other);
    }
    return core;
  }

  private Select selectCore() {
    expectKeyword("SELECT");
    boolean distinct = acceptKeyword("DISTINCT");
    List<SelectColumn> columns = new ArrayList<>();
    Map<Integer, List<Col>> excludes = new LinkedHashMap<>();
    do {
      columns.add(columnSpec(columns.size(), excludes, distinct));
    } while (acceptSymbol(","));
    expectKeyword("FROM");
    List<TableRef> tables = new ArrayList<>();
    tables.add(tableDef());
    List<Join> joins = new ArrayList<>();
    while (peek().isKeyword("JOIN") || peek().isKeyword("LEFT")) {
      if (acceptKeyword("LEFT")) expectKeyword("JOIN");
      else next();
      tables.add(tableDef());
      expectKeyword("ON");
      joins.add(new Join(tables.size() - 1, orExpr()));
    }
    Expr where = null;
    if (acceptKeyword("WHERE")) where = orExpr();
    List<OrderBy> order = new ArrayList<>();
    if (acceptKeyword("ORDER")) {
      expectKeyword("BY");
      do {
        String first = identifier();
        String table = null, field = first;
        if (acceptSymbol(".")) {
          table = first;
          field = identifier();
        }
        boolean asc = true;
        if (acceptKeyword("DESC")) asc = false;
        else acceptKeyword("ASC");
        order.add(new OrderBy(table, field, asc));
      } while (acceptSymbol(","));
    }
    long limit = -1, offset = 0;
    if (acceptKeyword("LIMIT")) limit = integerLiteral();
    if (acceptKeyword("OFFSET")) offset = integerLiteral();
    return new Select(
        distinct, columns, excludes, tables, joins, where, order, limit, offset, null);
  }

  private long integerLiteral() {
    Token t = peek();
    if (t.kind() != Kind.INTEGER) throw error(t);
    next();
    return t.value().i();
  }

  private TableRef tableDef() {
    Token t = peek();
    String ds = null, name;
    if (t.kind() == Kind.STRING) {
      next();
      ds = t.text();
      expectSymbol(".");
      name = identifier();
    } else {
      name = identifier();
      if (acceptSymbol(".")) {
        ds = name;
        name = identifier();
      }
    }
    String alias = null;
    if (acceptKeyword("AS")) alias = identifier();
    else if (isIdentifier(peek())) alias = identifier();
    return new TableRef(ds, name, alias == null ? name : alias);
  }

  private SelectColumn columnSpec(
      int index, Map<Integer, List<Col>> excludes, boolean selectDistinct) {
    Token t = peek();
    if (t.isSymbol("*")) {
      next();
      if (peek().isKeyword("EXCEPT") || peek().isKeyword("EXCLUDE")) {
        next();
        expectSymbol("(");
        List<Col> list = new ArrayList<>();
        do {
          String first = identifier();
          if (acceptSymbol(".")) list.add(new Col(first, identifier()));
          else list.add(new Col(null, first));
        } while (acceptSymbol(","));
        expectSymbol(")");
        excludes.put(index, list);
      }
      return new SelectColumn(new Col(null, "*"), null, false, false);
    }
    if (isIdentifier(t) && peek(1).isSymbol(".") && peek(2).isSymbol("*")) {
      String table = identifier();
      next();
      next();
      return new SelectColumn(new Col(table, "*"), null, false, false);
    }
    if (isIdentifier(t) && peek(1).isSymbol("(") && peek(2).isSymbol("*")) {
      String fn = identifier();
      if (!fn.equalsIgnoreCase("COUNT"))
        throw new JdalException("Syntax Error with " + fn + "(*).");
      next();
      next();
      expectSymbol(")");
      Expr count = new Op(SqlOp.COUNT, List.of(new Col(null, "*")));
      return aliased(count, false);
    }
    if (isIdentifier(t) && peek(1).isSymbol("(") && peek(2).isKeyword("DISTINCT")) {
      String fn = identifier();
      if (!fn.equalsIgnoreCase("COUNT")) {
        throw new JdalException("DISTINCT keyword can only be used in COUNT() operator.");
      }
      next();
      next();
      String first = identifier();
      Col c = acceptSymbol(".") ? new Col(first, identifier()) : new Col(null, first);
      expectSymbol(")");
      return aliased(new Op(SqlOp.COUNT, List.of(c)), true);
    }
    Expr e = orExpr();
    return aliased(e, false);
  }

  private SelectColumn aliased(Expr e, boolean distinct) {
    String alias = null;
    boolean hidden = false;
    if (acceptKeyword("AS")) alias = identifier();
    else if (isIdentifier(peek())) alias = identifier();
    if (alias != null && peek().isKeyword("HIDDEN")) {
      next();
      hidden = true;
    }
    return new SelectColumn(e, alias, distinct, hidden);
  }

  // ------------------------------------------------------------------ expressions

  private Expr orExpr() {
    Expr left = andExpr();
    while (peek().isKeyword("OR")) {
      next();
      Expr right = andExpr();
      left = checkDepth(flatten(SqlOp.OR, left, right));
    }
    return left;
  }

  private Expr andExpr() {
    Expr left = notExpr();
    while (peek().isKeyword("AND")) {
      next();
      Expr right = notExpr();
      left = checkDepth(flatten(SqlOp.AND, left, right));
    }
    return left;
  }

  /** AND/OR chains are n-ary so that long chains stay shallow, as GDAL does. */
  private static Expr flatten(SqlOp op, Expr left, Expr right) {
    List<Expr> args = new ArrayList<>();
    if (left instanceof Op l && l.op() == op) args.addAll(l.args());
    else args.add(left);
    if (right instanceof Op r && r.op() == op) args.addAll(r.args());
    else args.add(right);
    return new Op(op, args);
  }

  private Expr notExpr() {
    if (peek().isKeyword("NOT")) {
      next();
      return checkDepth(new Op(SqlOp.NOT, List.of(notExpr())));
    }
    return comparison();
  }

  private Expr comparison() {
    Expr left = additive();
    while (true) {
      Token t = peek();
      if (t.kind() == Kind.SYMBOL) {
        SqlOp op = comparisonOperator();
        if (op == null) return left;
        Expr right = additive();
        left = checkDepth(new Op(op, List.of(left, right)));
      } else if (t.isKeyword("IS")) {
        next();
        boolean not = acceptKeyword("NOT");
        expectKeyword("NULL");
        Expr isNull = checkDepth(new Op(SqlOp.ISNULL, List.of(left)));
        left = not ? checkDepth(new Op(SqlOp.NOT, List.of(isNull))) : isNull;
      } else if (t.isKeyword("LIKE")
          || t.isKeyword("ILIKE")
          || (t.isKeyword("NOT")
              && (peek(1).isKeyword("LIKE")
                  || peek(1).isKeyword("ILIKE")
                  || peek(1).isKeyword("IN")
                  || peek(1).isKeyword("BETWEEN")))) {
        boolean not = acceptKeyword("NOT");
        Token kw = next();
        Expr result;
        if (kw.isKeyword("IN")) {
          expectSymbol("(");
          List<Expr> args = new ArrayList<>();
          args.add(left);
          do {
            args.add(orExpr());
          } while (acceptSymbol(","));
          expectSymbol(")");
          result = checkDepth(new Op(SqlOp.IN, args));
        } else if (kw.isKeyword("BETWEEN")) {
          Expr low = additive();
          expectKeyword("AND");
          Expr high = additive();
          result = checkDepth(new Op(SqlOp.BETWEEN, List.of(left, low, high)));
        } else {
          SqlOp op = kw.isKeyword("LIKE") ? SqlOp.LIKE : SqlOp.ILIKE;
          Expr pattern = additive();
          if (acceptKeyword("ESCAPE")) {
            Expr esc = additive();
            result = checkDepth(new Op(op, List.of(left, pattern, esc)));
          } else {
            result = checkDepth(new Op(op, List.of(left, pattern)));
          }
        }
        left = not ? checkDepth(new Op(SqlOp.NOT, List.of(result))) : result;
      } else if (t.isKeyword("IN") || t.isKeyword("BETWEEN")) {
        next();
        if (t.isKeyword("IN")) {
          expectSymbol("(");
          List<Expr> args = new ArrayList<>();
          args.add(left);
          do {
            args.add(orExpr());
          } while (acceptSymbol(","));
          expectSymbol(")");
          left = checkDepth(new Op(SqlOp.IN, args));
        } else {
          Expr low = additive();
          expectKeyword("AND");
          Expr high = additive();
          left = checkDepth(new Op(SqlOp.BETWEEN, List.of(left, low, high)));
        }
      } else {
        return left;
      }
    }
  }

  /** Consumes a comparison operator made of one or two symbols; null if there is none. */
  private SqlOp comparisonOperator() {
    Token t = peek();
    String a = t.text();
    String b = peek(1).kind() == Kind.SYMBOL && peek(1).pos() == t.pos() + 1 ? peek(1).text() : "";
    switch (a) {
      case "=":
        if (b.equals("<")) {
          next();
          next();
          return SqlOp.LE;
        }
        if (b.equals(">")) {
          next();
          next();
          return SqlOp.GE;
        }
        next();
        return SqlOp.EQ;
      case "<":
        if (b.equals(">")) {
          next();
          next();
          return SqlOp.NE;
        }
        if (b.equals("=")) {
          next();
          next();
          return SqlOp.LE;
        }
        next();
        return SqlOp.LT;
      case ">":
        if (b.equals("=")) {
          next();
          next();
          return SqlOp.GE;
        }
        next();
        return SqlOp.GT;
      case "!":
        if (b.equals("=")) {
          next();
          next();
          return SqlOp.NE;
        }
        return null;
      default:
        return null;
    }
  }

  private Expr additive() {
    Expr left = multiplicative();
    while (peek().isSymbol("+") || peek().isSymbol("-")) {
      SqlOp op = next().text().equals("+") ? SqlOp.ADD : SqlOp.SUBTRACT;
      left = checkDepth(new Op(op, List.of(left, multiplicative())));
    }
    return left;
  }

  private Expr multiplicative() {
    Expr left = unary();
    while (peek().isSymbol("*") || peek().isSymbol("/") || peek().isSymbol("%")) {
      String s = next().text();
      SqlOp op = s.equals("*") ? SqlOp.MULTIPLY : s.equals("/") ? SqlOp.DIVIDE : SqlOp.MODULUS;
      left = checkDepth(new Op(op, List.of(left, unary())));
    }
    return left;
  }

  private Expr unary() {
    if (peek().isSymbol("-")) {
      next();
      Expr e = unary();
      if (e instanceof Const c && !c.value().isNull() && c.value().type() != SqlType.STRING) {
        Val v = c.value();
        if (v.type() == SqlType.FLOAT && "9223372036854775808".equals(c.text())) {
          return new Const(Val.ofInt(SqlType.INTEGER64, Long.MIN_VALUE), "-9223372036854775808");
        }
        if (v.type() == SqlType.INTEGER64 && v.i() == Long.MIN_VALUE) return e;
        if (v.type() == SqlType.FLOAT) return new Const(Val.real(-v.f()), null);
        return new Const(Val.integer(-v.i()), null);
      }
      return checkDepth(new Op(SqlOp.MULTIPLY, List.of(new Const(Val.integer(-1), null), e)));
    }
    return primary();
  }

  private Expr primary() {
    Token t = peek();
    switch (t.kind()) {
      case INTEGER, FLOAT -> {
        next();
        return new Const(t.value(), t.text());
      }
      case STRING -> {
        next();
        return new Const(t.value(), null);
      }
      case KEYWORD -> {
        if (t.isKeyword("NULL")) {
          next();
          return new Const(Val.string(null), null);
        }
        if (t.isKeyword("CAST")) return cast();
        if (t.isKeyword("HIDDEN")) return columnOrCall();
        throw error(t);
      }
      case SYMBOL -> {
        if (t.isSymbol("(")) {
          next();
          Expr e = orExpr();
          expectSymbol(")");
          return e;
        }
        throw error(t);
      }
      case IDENTIFIER -> {
        return columnOrCall();
      }
      default -> throw error(t);
    }
  }

  private Expr columnOrCall() {
    String name = identifier();
    if (peek().isSymbol("(")) {
      next();
      SqlOp op = SqlOp.function(name);
      List<Expr> args = new ArrayList<>();
      do {
        args.add(orExpr());
      } while (acceptSymbol(","));
      expectSymbol(")");
      if (op == null) throw new JdalException("Undefined function '" + name + "' used.");
      return checkDepth(new Op(op, args));
    }
    if (acceptSymbol(".")) return new Col(name, identifier());
    return new Col(null, name);
  }

  private Expr cast() {
    next();
    expectSymbol("(");
    Expr arg = orExpr();
    expectKeyword("AS");
    String type = identifier();
    int width = -1, precision = -1, srid = 0;
    String geomType = null;
    if (acceptSymbol("(")) {
      Token t = peek();
      if (t.kind() == Kind.INTEGER) {
        next();
        width = (int) t.value().i();
        if (acceptSymbol(",")) {
          Token p = peek();
          if (p.kind() != Kind.INTEGER) throw error(p);
          next();
          precision = (int) p.value().i();
        }
      } else if (isIdentifier(t)) {
        geomType = identifier();
        if (!type.equalsIgnoreCase("GEOMETRY") || !isGeometryTypeName(geomType)) throw error(t);
        if (acceptSymbol(",")) {
          Token p = peek();
          if (p.kind() != Kind.INTEGER) throw error(p);
          next();
          srid = (int) p.value().i();
        }
      } else {
        throw error(t);
      }
      expectSymbol(")");
    }
    expectSymbol(")");
    return checkDepth(new Cast(arg, type, width, precision, geomType, srid));
  }

  static boolean isGeometryTypeName(String n) {
    String u = n.toUpperCase(Locale.ROOT);
    if (u.endsWith("Z")) u = u.substring(0, u.length() - 1);
    return u.startsWith("GEOMETRY")
        || List.of(
                "POINT",
                "LINESTRING",
                "POLYGON",
                "MULTIPOINT",
                "MULTILINESTRING",
                "MULTIPOLYGON",
                "GEOMETRYCOLLECTION")
            .contains(u);
  }
}
