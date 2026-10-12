package dev.jdal.filter;

import dev.jdal.feature.FeatureType;
import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldSubType;
import dev.jdal.sql.WhereFilter;
import dev.jdal.sql.WhereFilter.Cmp;
import dev.jdal.sql.WhereFilter.Operand;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Walks a {@link WhereFilter} and asks a syntax to write it. Everything that would not behave as
 * OGR SQL does is refused here: operands must be properties of a numeric or string type compared
 * with literals of the same kind, and comparisons of strings ignore case as in OGR SQL (LIKE does
 * not, ILIKE does).
 */
abstract class FilterTranslator {

  /** A translatable property or literal, written in the target syntax. */
  record Term(String text, boolean string) {}

  // ------------------------------------------------------------------ what a syntax provides

  abstract String property(String name);

  abstract String number(String text);

  abstract String string(String text);

  /** null if the syntax cannot say it (for instance a case-insensitive comparison). */
  abstract String compare(Cmp op, Term left, Term right);

  abstract String like(Term property, Term pattern, boolean caseless);

  abstract String isNull(Term property);

  abstract String in(Term property, List<Term> items);

  abstract String between(Term property, Term low, Term high);

  abstract String and(List<String> parts);

  abstract String or(List<String> parts);

  abstract String not(String part);

  // ------------------------------------------------------------------ translation

  Translation run(String where, FeatureType type, Predicate<String> queryable) {
    WhereFilter filter = WhereFilter.parse(where);
    List<String> done = new ArrayList<>();
    boolean complete = true;
    for (WhereFilter c : filter.conjuncts()) {
      String s = node(c, type, queryable);
      if (s == null) complete = false;
      else done.add(s);
    }
    if (done.isEmpty()) return Translation.NONE;
    return new Translation(done.size() == 1 ? done.getFirst() : and(done), complete);
  }

  private String node(WhereFilter f, FeatureType type, Predicate<String> queryable) {
    switch (f) {
      case WhereFilter.And a:
        return all(a.parts(), type, queryable, true);
      case WhereFilter.Or o:
        return all(o.parts(), type, queryable, false);
      case WhereFilter.Not n:
        {
          String s = node(n.part(), type, queryable);
          return s == null ? null : not(s);
        }
      case WhereFilter.Compare c:
        {
          Term[] t = pair(c.left(), c.right(), type, queryable);
          if (t == null) return null;
          Cmp op = c.left() instanceof Operand.Property ? c.op() : flip(c.op());
          return compare(op, t[0], t[1]);
        }
      case WhereFilter.Like l:
        {
          if (l.escape() != null && !l.escape().equals("\\")) return null;
          Term[] t = pair(l.value(), l.pattern(), type, queryable);
          if (t == null || !(l.value() instanceof Operand.Property) || !t[0].string()) return null;
          return like(t[0], t[1], l.caseInsensitive());
        }
      case WhereFilter.IsNull n:
        {
          Term p = property(n.value(), type, queryable);
          return p == null ? null : isNull(p);
        }
      case WhereFilter.In in:
        {
          Term p = property(in.value(), type, queryable);
          if (p == null || in.items().isEmpty()) return null;
          List<Term> items = new ArrayList<>();
          for (Operand o : in.items()) {
            Term lit = literal(o, p.string());
            if (lit == null) return null;
            items.add(lit);
          }
          return in(p, items);
        }
      case WhereFilter.Between b:
        {
          Term p = property(b.value(), type, queryable);
          if (p == null) return null;
          Term lo = literal(b.low(), p.string());
          Term hi = literal(b.high(), p.string());
          return lo == null || hi == null ? null : between(p, lo, hi);
        }
      default:
        return null;
    }
  }

  /** AND needs only some parts (the rest is checked later); OR and NOT need all of them. */
  private String all(
      List<WhereFilter> parts, FeatureType type, Predicate<String> queryable, boolean isAnd) {
    List<String> out = new ArrayList<>();
    for (WhereFilter p : parts) {
      String s = node(p, type, queryable);
      if (s == null) return null; // inside a larger expression a part cannot be left out
      out.add(s);
    }
    if (out.size() == 1) return out.getFirst();
    return isAnd ? and(out) : or(out);
  }

  private static Cmp flip(Cmp c) {
    return switch (c) {
      case LT -> Cmp.GT;
      case LE -> Cmp.GE;
      case GT -> Cmp.LT;
      case GE -> Cmp.LE;
      default -> c;
    };
  }

  /** A property of a type we can compare, as a term; null if not usable. */
  private Term property(Operand o, FeatureType type, Predicate<String> queryable) {
    if (!(o instanceof Operand.Property p)) return null;
    var idx = type.fieldIndex(p.name());
    if (idx.isEmpty()) return null;
    FieldDefn d = type.fields().get(idx.getAsInt());
    if (!queryable.test(d.name()) || d.subType() == FieldSubType.BOOLEAN) return null;
    return switch (d.type()) {
      case INTEGER, INTEGER64, REAL -> new Term(property(d.name()), false);
      case STRING -> new Term(property(d.name()), true);
      default -> null;
    };
  }

  private Term literal(Operand o, boolean string) {
    if (!(o instanceof Operand.Literal l)) return null;
    if (string) return l.kind() == Operand.Kind.STRING ? new Term(string(l.text()), true) : null;
    if (l.kind() == Operand.Kind.INTEGER || l.kind() == Operand.Kind.REAL) {
      return l.text().matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
          ? new Term(number(l.text()), false)
          : null;
    }
    return null;
  }

  /** A property and a literal in either order, as [property, literal]. */
  private Term[] pair(Operand a, Operand b, FeatureType type, Predicate<String> queryable) {
    Operand prop = a instanceof Operand.Property ? a : b;
    Operand lit = a instanceof Operand.Property ? b : a;
    Term p = property(prop, type, queryable);
    if (p == null) return null;
    Term l = literal(lit, p.string());
    return l == null ? null : new Term[] {p, l};
  }
}
