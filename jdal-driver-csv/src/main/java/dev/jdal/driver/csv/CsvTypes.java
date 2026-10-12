package dev.jdal.driver.csv;

import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldSubType;
import dev.jdal.feature.FieldType;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Type names of {@code .csvt} files and the classification of values for type autodetection. */
final class CsvTypes {

  private CsvTypes() {}

  /** Role of a column when it is not a plain field. */
  enum Role {
    FIELD,
    WKT,
    X,
    Y
  }

  /** A parsed .csvt entry. */
  record Column(Role role, FieldDefn defn) {}

  private static final Pattern ENTRY =
      Pattern.compile("\\s*([A-Za-z0-9]+)\\s*(?:\\(([^)]*)\\))?\\s*");

  /**
   * @return null if {@code text} is not a recognised type
   */
  static Column parseCsvt(String name, String text) {
    Matcher m = ENTRY.matcher(text);
    if (!m.matches()) {
      // "Point(X)" style
      return null;
    }
    String t = m.group(1).toLowerCase(Locale.ROOT);
    String arg = m.group(2) == null ? null : m.group(2).strip();
    if (t.equals("point") && arg != null) {
      return switch (arg.toUpperCase(Locale.ROOT)) {
        case "X" -> new Column(Role.X, FieldDefn.of(name, FieldType.REAL));
        case "Y" -> new Column(Role.Y, FieldDefn.of(name, FieldType.REAL));
        default -> null;
      };
    }
    FieldDefn f;
    switch (t) {
      case "integer" -> f = FieldDefn.of(name, FieldType.INTEGER);
      case "integer64" -> f = FieldDefn.of(name, FieldType.INTEGER64);
      case "real" -> f = FieldDefn.of(name, FieldType.REAL);
      case "string" -> f = FieldDefn.of(name, FieldType.STRING);
      case "date" -> f = FieldDefn.of(name, FieldType.DATE);
      case "time" -> f = FieldDefn.of(name, FieldType.TIME);
      case "datetime" -> f = FieldDefn.of(name, FieldType.DATETIME);
      case "binary" -> f = FieldDefn.of(name, FieldType.BINARY);
      case "jsonstringlist" -> f = FieldDefn.of(name, FieldType.STRING_LIST);
      case "jsonintegerlist" -> f = FieldDefn.of(name, FieldType.INTEGER_LIST);
      case "jsoninteger64list" -> f = FieldDefn.of(name, FieldType.INTEGER64_LIST);
      case "jsonreallist" -> f = FieldDefn.of(name, FieldType.REAL_LIST);
      case "wkt" -> {
        return new Column(Role.WKT, FieldDefn.of(name, FieldType.STRING));
      }
      case "coordx" -> {
        return new Column(Role.X, FieldDefn.of(name, FieldType.REAL));
      }
      case "coordy" -> {
        return new Column(Role.Y, FieldDefn.of(name, FieldType.REAL));
      }
      default -> {
        return null;
      }
    }
    if (arg != null && !arg.isEmpty()) {
      if (Character.isDigit(arg.charAt(0))) {
        String[] wp = arg.split("\\.");
        try {
          f = f.withWidth(Integer.parseInt(wp[0]), wp.length > 1 ? Integer.parseInt(wp[1]) : 0);
        } catch (NumberFormatException e) {
          return null;
        }
      } else {
        switch (arg.toLowerCase(Locale.ROOT)) {
          case "boolean" -> f = f.withType(f.type(), FieldSubType.BOOLEAN);
          case "int16" -> f = f.withType(f.type(), FieldSubType.INT16);
          case "float32" -> f = f.withType(f.type(), FieldSubType.FLOAT32);
          case "json" -> f = f.withType(f.type(), FieldSubType.JSON);
          case "uuid" -> f = f.withType(f.type(), FieldSubType.UUID);
          default -> {}
        }
      }
    }
    return new Column(Role.FIELD, f);
  }

  /** The .csvt text for a field ("Integer(5)", "Real(10.7)", "Integer(Boolean)", ...). */
  static String csvtName(FieldDefn f) {
    if (f.subType() == FieldSubType.BOOLEAN) return "Integer(Boolean)";
    if (f.subType() == FieldSubType.INT16) return "Integer(Int16)";
    if (f.subType() == FieldSubType.FLOAT32) return "Real(Float32)";
    String w =
        f.width() > 0 ? "(" + f.width() + (f.precision() > 0 ? "." + f.precision() : "") + ")" : "";
    return switch (f.type()) {
      case INTEGER -> "Integer" + w;
      case INTEGER64 -> "Integer64" + w;
      case REAL -> "Real" + w;
      case STRING -> "String" + w;
      case BINARY -> "Binary";
      case DATE -> "Date";
      case TIME -> "Time";
      case DATETIME -> "DateTime";
      case INTEGER_LIST -> "JSonIntegerList";
      case INTEGER64_LIST -> "JSonInteger64List";
      case REAL_LIST -> "JSonRealList";
      case STRING_LIST -> "JSonStringList";
    };
  }

  // ------------------------------------------------------------------ autodetection

  enum Kind {
    EMPTY,
    BOOL,
    INT,
    INT64,
    REAL,
    DATE,
    TIME,
    DATETIME,
    STRING
  }

  private static final Set<String> TRUE_WORDS = Set.of("y", "yes", "t", "true", "on");
  private static final Set<String> FALSE_WORDS = Set.of("n", "no", "f", "false", "off");
  private static final Pattern INT = Pattern.compile("[+-]?\\d+");
  private static final Pattern REAL =
      Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");
  private static final Pattern DATE = Pattern.compile("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}");
  private static final Pattern TIME = Pattern.compile("\\d{1,2}:\\d{2}(:\\d{2}([.,]\\d+)?)?");
  private static final Pattern DATETIME =
      Pattern.compile(
          "\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}["
              + " T]\\d{1,2}:\\d{2}(:\\d{2}([.,]\\d+)?)?\\s*(Z|[+-]\\d{2}(:?\\d{2})?)?");

  static boolean isBooleanWord(String v) {
    String l = v.toLowerCase(Locale.ROOT);
    return TRUE_WORDS.contains(l) || FALSE_WORDS.contains(l);
  }

  static boolean booleanWordValue(String v) {
    return TRUE_WORDS.contains(v.toLowerCase(Locale.ROOT));
  }

  static Kind classify(String v) {
    String t = v.strip();
    if (t.isEmpty()) return Kind.EMPTY;
    if (isBooleanWord(t)) return Kind.BOOL;
    if (INT.matcher(t).matches()) {
      try {
        Integer.parseInt(t.startsWith("+") ? t.substring(1) : t);
        return Kind.INT;
      } catch (NumberFormatException e) {
        try {
          Long.parseLong(t.startsWith("+") ? t.substring(1) : t);
          return Kind.INT64;
        } catch (NumberFormatException e2) {
          return Kind.REAL;
        }
      }
    }
    if (REAL.matcher(t).matches()) return Kind.REAL;
    String l = t.toLowerCase(Locale.ROOT);
    if (l.equals("inf")
        || l.equals("-inf")
        || l.equals("+inf")
        || l.equals("nan")
        || l.equals("infinity")
        || l.equals("-infinity")) {
      return Kind.REAL;
    }
    if (DATE.matcher(t).matches()) return Kind.DATE;
    if (TIME.matcher(t).matches()) return Kind.TIME;
    if (DATETIME.matcher(t).matches()) return Kind.DATETIME;
    return Kind.STRING;
  }

  static Kind merge(Kind a, Kind b) {
    if (a == b) return a;
    if (a == Kind.EMPTY) return b;
    if (b == Kind.EMPTY) return a;
    if (a == Kind.STRING || b == Kind.STRING) return Kind.STRING;
    boolean an = a == Kind.INT || a == Kind.INT64 || a == Kind.REAL,
        bn = b == Kind.INT || b == Kind.INT64 || b == Kind.REAL;
    if (an && bn) return (a == Kind.REAL || b == Kind.REAL) ? Kind.REAL : Kind.INT64;
    if ((a == Kind.DATE && b == Kind.DATETIME) || (a == Kind.DATETIME && b == Kind.DATE))
      return Kind.DATETIME;
    return Kind.STRING;
  }

  /** Parses the doubles CSV files contain, including inf and nan. */
  static double parseDouble(String t) {
    String l = t.strip().toLowerCase(Locale.ROOT);
    return switch (l) {
      case "inf", "+inf", "infinity", "+infinity" -> Double.POSITIVE_INFINITY;
      case "-inf", "-infinity" -> Double.NEGATIVE_INFINITY;
      case "nan", "-nan", "+nan" -> Double.NaN;
      default -> Double.parseDouble(t.strip());
    };
  }
}
