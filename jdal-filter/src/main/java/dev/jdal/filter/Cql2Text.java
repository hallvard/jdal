package dev.jdal.filter;

import dev.jdal.feature.FeatureType;
import dev.jdal.sql.WhereFilter.Cmp;
import java.util.List;
import java.util.function.Predicate;

/**
 * OGR SQL WHERE clauses as CQL2 text (the {@code filter} parameter of OGC API - Features, part 3).
 * Comparisons of strings are written with {@code CASEI()} on both sides, as OGR SQL ignores case
 * there; a service that does not know the function will refuse the request.
 */
public final class Cql2Text extends FilterTranslator {

  private static final Cql2Text INSTANCE = new Cql2Text();

  private Cql2Text() {}

  /**
   * @param queryable which properties the service accepts in filters
   */
  public static Translation translate(String where, FeatureType type, Predicate<String> queryable) {
    return INSTANCE.run(where, type, queryable);
  }

  public static Translation translate(String where, FeatureType type) {
    return translate(where, type, name -> true);
  }

  @Override
  String property(String name) {
    return name.matches("[A-Za-z_][A-Za-z0-9_.]*") ? name : '"' + name.replace("\"", "\"\"") + '"';
  }

  @Override
  String number(String text) {
    return text.startsWith("+") ? text.substring(1) : text;
  }

  @Override
  String string(String text) {
    return "'" + text.replace("'", "''") + "'";
  }

  private static String caseless(Term t) {
    return t.string() ? "CASEI(" + t.text() + ")" : t.text();
  }

  @Override
  String compare(Cmp op, Term left, Term right) {
    String sym =
        switch (op) {
          case EQ -> "=";
          case NE -> "<>";
          case LT -> "<";
          case LE -> "<=";
          case GT -> ">";
          case GE -> ">=";
        };
    return caseless(left) + " " + sym + " " + caseless(right);
  }

  @Override
  String like(Term property, Term pattern, boolean caseless) {
    return caseless
        ? "CASEI(" + property.text() + ") LIKE CASEI(" + pattern.text() + ")"
        : property.text() + " LIKE " + pattern.text();
  }

  @Override
  String isNull(Term property) {
    return property.text() + " IS NULL";
  }

  @Override
  String in(Term property, List<Term> items) {
    return caseless(property)
        + " IN ("
        + String.join(", ", items.stream().map(Cql2Text::caseless).toList())
        + ")";
  }

  @Override
  String between(Term property, Term low, Term high) {
    return caseless(property) + " BETWEEN " + caseless(low) + " AND " + caseless(high);
  }

  @Override
  String and(List<String> parts) {
    return String.join(" AND ", parts.stream().map(Cql2Text::group).toList());
  }

  @Override
  String or(List<String> parts) {
    return String.join(" OR ", parts.stream().map(Cql2Text::group).toList());
  }

  @Override
  String not(String part) {
    return "NOT (" + part + ")";
  }

  /** Parentheses around anything that is not a single condition. */
  private static String group(String s) {
    return s.contains(" AND ") || s.contains(" OR ") || s.startsWith("NOT ") ? "(" + s + ")" : s;
  }
}
