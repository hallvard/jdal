package dev.jdal.driver.csv;

import dev.jdal.core.JdalException;
import dev.jdal.core.Options;
import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldSubType;
import dev.jdal.feature.FieldType;
import dev.jdal.feature.FieldValues;
import dev.jdal.geom.Geometry;
import dev.jdal.geom.Point;
import dev.jdal.geom.Wkt;
import java.util.ArrayList;
import java.util.List;

/** Output conventions of the CSV writer: layer creation options and the text of values. */
record CsvFormat(
    char separator,
    String eol,
    Quoting quoting,
    boolean bom,
    boolean csvt,
    Geom geom,
    boolean header) {

  enum Quoting {
    ALWAYS,
    IF_AMBIGUOUS,
    IF_NEEDED
  }

  enum Geom {
    NONE,
    AS_WKT,
    AS_XY,
    AS_XYZ,
    AS_YX
  }

  static CsvFormat from(Options o) {
    String sep = o.choice("SEPARATOR", "COMMA", "COMMA", "SEMICOLON", "TAB", "SPACE", "PIPE");
    String eol =
        o.choice("LINEFORMAT", System.lineSeparator().equals("\r\n") ? "CRLF" : "LF", "CRLF", "LF");
    return new CsvFormat(
        CsvSchema.separatorFor(sep),
        eol.equals("CRLF") ? "\r\n" : "\n",
        Quoting.valueOf(
            o.choice("STRING_QUOTING", "IF_AMBIGUOUS", "ALWAYS", "IF_AMBIGUOUS", "IF_NEEDED")),
        o.bool("WRITE_BOM", false),
        o.bool("CREATE_CSVT", false),
        Geom.valueOf(o.choice("GEOMETRY", "NONE", "NONE", "AS_WKT", "AS_XY", "AS_XYZ", "AS_YX")),
        o.bool("HEADER", true));
  }

  /** One output column. */
  record Column(
      String name,
      String csvtType,
      java.util.function.Function<dev.jdal.feature.Feature, String> text,
      boolean isString) {}

  /** Output columns for {@code fields} (and the geometry according to {@code geom}). */
  List<Column> columns(List<FieldDefn> fields) {
    List<Column> cols = new ArrayList<>();
    switch (geom) {
      case AS_WKT ->
          cols.add(
              new Column(
                  "WKT", "WKT", f -> f.geometry() == null ? null : f.geometry().toWkt(), true));
      case AS_XY -> {
        cols.add(new Column("X", "Real", f -> coordinate(f.geometry(), 0), false));
        cols.add(new Column("Y", "Real", f -> coordinate(f.geometry(), 1), false));
      }
      case AS_YX -> {
        cols.add(new Column("Y", "Real", f -> coordinate(f.geometry(), 1), false));
        cols.add(new Column("X", "Real", f -> coordinate(f.geometry(), 0), false));
      }
      case AS_XYZ -> {
        cols.add(new Column("X", "Real", f -> coordinate(f.geometry(), 0), false));
        cols.add(new Column("Y", "Real", f -> coordinate(f.geometry(), 1), false));
        cols.add(new Column("Z", "Real", f -> coordinate(f.geometry(), 2), false));
      }
      case NONE -> {}
    }
    for (int i = 0; i < fields.size(); i++) {
      FieldDefn d = fields.get(i);
      final int idx = i;
      cols.add(
          new Column(
              d.name(),
              CsvTypes.csvtName(d),
              f -> valueText(d, f.get(idx)),
              d.type() == FieldType.STRING));
    }
    return cols;
  }

  private static String coordinate(Geometry g, int axis) {
    if (!(g instanceof Point p)) {
      if (g != null)
        throw new JdalException(
            "GEOMETRY=AS_XY/AS_XYZ/AS_YX needs point geometries, got " + g.type().displayName());
      return null;
    }
    if (p.isEmpty()) return null;
    double v = axis == 0 ? p.x() : axis == 1 ? p.y() : p.dimension().hasZ() ? p.z() : 0.0;
    return Wkt.number(v);
  }

  static String valueText(FieldDefn d, Object v) {
    if (v == null) return null;
    if (d.subType() == FieldSubType.BOOLEAN && v instanceof Number n)
      return n.intValue() != 0 ? "1" : "0";
    if (v instanceof List<?> l && d.type().isList()) {
      return dev.jdal.json.Json.write(dev.jdal.json.Json.of(l));
    }
    return FieldValues.format(d, v);
  }

  /** Escapes one cell. {@code isString} cells are quoted according to {@link #quoting}. */
  String cell(String text, boolean isString, boolean forceQuote) {
    if (text == null) return "";
    boolean needs =
        forceQuote
            || text.indexOf(separator) >= 0
            || text.indexOf('"') >= 0
            || text.indexOf('\n') >= 0
            || text.indexOf('\r') >= 0;
    if (isString && !needs) {
      if (quoting == Quoting.ALWAYS) needs = true;
      else if (quoting == Quoting.IF_AMBIGUOUS && !text.isEmpty() && looksLikeNumber(text))
        needs = true;
    }
    return needs ? '"' + text.replace("\"", "\"\"") + '"' : text;
  }

  private static boolean looksLikeNumber(String t) {
    try {
      Double.parseDouble(t.strip());
      return !t.strip().toLowerCase(java.util.Locale.ROOT).matches(".*[a-z&&[^e]].*");
    } catch (NumberFormatException e) {
      return false;
    }
  }

  String row(List<String> cells) {
    // an empty line would be skipped by readers, so a lone empty value must be written as ""
    if (cells.size() == 1 && cells.getFirst().isEmpty()) return "\"\"" + eol;
    return String.join(String.valueOf(separator), cells) + eol;
  }
}
