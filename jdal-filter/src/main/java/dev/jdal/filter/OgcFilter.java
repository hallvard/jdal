package dev.jdal.filter;

import dev.jdal.feature.FeatureType;
import dev.jdal.sql.WhereFilter.Cmp;
import java.util.List;
import java.util.function.Predicate;

/**
 * OGR SQL WHERE clauses as OGC filter encoding for WFS {@code FILTER} parameters: Filter 1.0 (WFS
 * 1.0), Filter 1.1 (WFS 1.1) and FES 2.0 (WFS 2.0). Comparisons of strings carry {@code
 * matchCase="false"} (OGR SQL ignores case there); Filter 1.0 has no such attribute, so they are
 * not translated for WFS 1.0.
 */
public final class OgcFilter extends FilterTranslator {

  /** The version of the filter encoding that goes with a WFS version. */
  public enum Version {
    FILTER_1_0("ogc", "http://www.opengis.net/ogc", "PropertyName", "escape"),
    FILTER_1_1("ogc", "http://www.opengis.net/ogc", "PropertyName", "escapeChar"),
    FES_2_0("fes", "http://www.opengis.net/fes/2.0", "ValueReference", "escapeChar");

    private final String prefix;
    private final String namespace;
    private final String property;
    private final String escapeAttribute;

    Version(String prefix, String namespace, String property, String escapeAttribute) {
      this.prefix = prefix;
      this.namespace = namespace;
      this.property = property;
      this.escapeAttribute = escapeAttribute;
    }

    public static Version forWfs(String wfsVersion) {
      if (wfsVersion.startsWith("2.")) return FES_2_0;
      if (wfsVersion.startsWith("1.0")) return FILTER_1_0;
      return FILTER_1_1;
    }
  }

  /**
   * A spatial condition on a geometry property: the property's value is in relation {@code op} to
   * the geometry, given as a GML element.
   */
  public static String spatial(
      Version version, dev.jdal.core.SpatialOp op, String property, String gmlGeometry) {
    OgcFilter f = new OgcFilter(version);
    String name =
        switch (op) {
          case INTERSECTS -> "Intersects";
          case WITHIN -> "Within";
          case CONTAINS -> "Contains";
          case DISJOINT -> "Disjoint";
          case TOUCHES -> "Touches";
          case CROSSES -> "Crosses";
          case OVERLAPS -> "Overlaps";
          case EQUALS -> "Equals";
        };
    return f.el(name, "", f.property(property) + gmlGeometry);
  }

  /** Conditions joined by AND (a single one is returned as it is). */
  public static String and(Version version, List<String> conditions) {
    return conditions.size() == 1 ? conditions.getFirst() : new OgcFilter(version).and(conditions);
  }

  private final Version v;

  private OgcFilter(Version v) {
    this.v = v;
  }

  /** The condition as XML elements (without the {@code Filter} element). */
  public static Translation translate(
      String where, FeatureType type, Version version, Predicate<String> queryable) {
    return new OgcFilter(version).run(where, type, queryable);
  }

  public static Translation translate(String where, FeatureType type, Version version) {
    return translate(where, type, version, name -> true);
  }

  /** Wraps a translated condition in a {@code Filter} element declaring its namespace. */
  public static String filterElement(String condition, Version version) {
    return "<"
        + version.prefix
        + ":Filter xmlns:"
        + version.prefix
        + "=\""
        + version.namespace
        + "\">"
        + condition
        + "</"
        + version.prefix
        + ":Filter>";
  }

  private String el(String name, String attrs, String content) {
    return "<" + v.prefix + ":" + name + attrs + ">" + content + "</" + v.prefix + ":" + name + ">";
  }

  static String escape(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  @Override
  String property(String name) {
    return el(v.property, "", escape(name));
  }

  @Override
  String number(String text) {
    return el("Literal", "", escape(text.startsWith("+") ? text.substring(1) : text));
  }

  @Override
  String string(String text) {
    return el("Literal", "", escape(text));
  }

  @Override
  String compare(Cmp op, Term left, Term right) {
    boolean strings = left.string();
    if (strings && v == Version.FILTER_1_0) return null; // no matchCase in Filter 1.0
    String name =
        switch (op) {
          case EQ -> "PropertyIsEqualTo";
          case NE -> "PropertyIsNotEqualTo";
          case LT -> "PropertyIsLessThan";
          case LE -> "PropertyIsLessThanOrEqualTo";
          case GT -> "PropertyIsGreaterThan";
          case GE -> "PropertyIsGreaterThanOrEqualTo";
        };
    return el(name, strings ? " matchCase=\"false\"" : "", left.text() + right.text());
  }

  @Override
  String like(Term property, Term pattern, boolean caseless) {
    if (caseless && v == Version.FILTER_1_0) return null;
    String attrs =
        " wildCard=\"%\" singleChar=\"_\" "
            + v.escapeAttribute
            + "=\"\\\""
            + (caseless ? " matchCase=\"false\"" : "");
    return el("PropertyIsLike", attrs, property.text() + pattern.text());
  }

  @Override
  String isNull(Term property) {
    return el("PropertyIsNull", "", property.text());
  }

  @Override
  String in(Term property, List<Term> items) {
    List<String> eq = items.stream().map(i -> compare(Cmp.EQ, property, i)).toList();
    if (eq.contains(null)) return null;
    return eq.size() == 1 ? eq.getFirst() : or(eq);
  }

  @Override
  String between(Term property, Term low, Term high) {
    if (property.string()) return null; // no case-insensitive PropertyIsBetween
    return el(
        "PropertyIsBetween",
        "",
        property.text()
            + el("LowerBoundary", "", low.text())
            + el("UpperBoundary", "", high.text()));
  }

  @Override
  String and(List<String> parts) {
    return el("And", "", String.join("", parts));
  }

  @Override
  String or(List<String> parts) {
    return el("Or", "", String.join("", parts));
  }

  @Override
  String not(String part) {
    return el("Not", "", part);
  }
}
