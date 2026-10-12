package dev.jdal.feature;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Conversion of arbitrary Java values to the canonical value class of a {@link FieldDefn}. */
public final class FieldValues {

  private FieldValues() {}

  /**
   * Converts {@code v} to the canonical Java type for {@code defn}; null stays null.
   *
   * @throws IllegalArgumentException if the value cannot be represented
   */
  public static Object coerce(FieldDefn defn, Object v) {
    if (v == null) return null;
    try {
      return switch (defn.type()) {
        case INTEGER -> toInt(v);
        case INTEGER64 -> toLong(v);
        case REAL ->
            defn.subType() == FieldSubType.FLOAT32
                ? Double.valueOf(toDouble(v).floatValue())
                : toDouble(v);
        case STRING ->
            v instanceof String ? v : v instanceof List<?> l ? listText(l) : String.valueOf(v);
        case BINARY -> toBytes(v);
        case DATE ->
            v instanceof LocalDate
                ? v
                : v instanceof LocalDateTime t
                    ? t.toLocalDate()
                    : v instanceof OffsetDateTime o ? o.toLocalDate() : parseDate(v.toString());
        case TIME ->
            v instanceof LocalTime
                ? v
                : v instanceof LocalDateTime t ? t.toLocalTime() : parseTime(v.toString());
        case DATETIME -> toDateTime(v);
        case INTEGER_LIST -> toList(v, FieldValues::toInt);
        case INTEGER64_LIST -> toList(v, FieldValues::toLong);
        case REAL_LIST -> toList(v, FieldValues::toDouble);
        case STRING_LIST -> toList(v, String::valueOf);
      };
    } catch (NumberFormatException | DateTimeParseException | ClassCastException e) {
      throw new IllegalArgumentException(
          "Cannot convert "
              + v.getClass().getSimpleName()
              + " '"
              + v
              + "' to "
              + defn.type()
              + " field "
              + defn.name(),
          e);
    }
  }

  /** A list stored in a string field: compact JSON, e.g. {@code [1,2]} or {@code ["a","b"]}. */
  private static String listText(List<?> l) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < l.size(); i++) {
      if (i > 0) sb.append(',');
      Object o = l.get(i);
      if (o == null) sb.append("null");
      else if (o instanceof Number || o instanceof Boolean) sb.append(o);
      else
        sb.append('"').append(o.toString().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }
    return sb.append(']').toString();
  }

  private static Integer toInt(Object v) {
    if (v instanceof Integer i) return i;
    if (v instanceof Boolean b) return b ? 1 : 0;
    if (v instanceof Number n) {
      double d = n.doubleValue();
      if (n instanceof Long l) {
        if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE)
          throw new NumberFormatException("out of range: " + l);
        return (int) (long) l;
      }
      if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE || Double.isNaN(d))
        throw new NumberFormatException("out of range: " + d);
      return (int) d;
    }
    return Integer.valueOf(v.toString().strip());
  }

  private static Long toLong(Object v) {
    if (v instanceof Long l) return l;
    if (v instanceof Boolean b) return b ? 1L : 0L;
    if (v instanceof Number n) return n.longValue();
    return Long.valueOf(v.toString().strip());
  }

  private static Double toDouble(Object v) {
    if (v instanceof Double d) return d;
    if (v instanceof Number n) return n.doubleValue();
    return Double.valueOf(v.toString().strip());
  }

  private static byte[] toBytes(Object v) {
    if (v instanceof byte[] b) return b;
    if (v instanceof String s) return java.util.HexFormat.of().parseHex(s);
    throw new ClassCastException(v.getClass().getName());
  }

  private static Object toDateTime(Object v) {
    if (v instanceof LocalDateTime || v instanceof OffsetDateTime) return v;
    if (v instanceof LocalDate d) return d.atStartOfDay();
    return parseDateTime(v.toString());
  }

  private static final java.util.regex.Pattern DATE_RE =
      java.util.regex.Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})");
  private static final java.util.regex.Pattern TIME_RE =
      java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?");
  private static final java.util.regex.Pattern TZ_RE =
      java.util.regex.Pattern.compile("(Z|[+-]\\d{2}(?::?\\d{2})?)");

  /** Accepts {@code YYYY-MM-DD} and {@code YYYY/MM/DD}. */
  public static LocalDate parseDate(String text) {
    var m = DATE_RE.matcher(text.strip());
    if (!m.matches()) throw new DateTimeParseException("Not a date", text, 0);
    return LocalDate.of(
        Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
  }

  /** Accepts {@code HH:MM[:SS[.fff]]}. */
  public static LocalTime parseTime(String text) {
    var m = TIME_RE.matcher(text.strip());
    if (!m.matches()) throw new DateTimeParseException("Not a time", text, 0);
    return time(m);
  }

  private static LocalTime time(java.util.regex.Matcher m) {
    int nanos =
        m.group(4) == null ? 0 : Integer.parseInt((m.group(4) + "000000000").substring(0, 9));
    return LocalTime.of(
        Integer.parseInt(m.group(1)),
        Integer.parseInt(m.group(2)),
        m.group(3) == null ? 0 : Integer.parseInt(m.group(3)),
        nanos);
  }

  /**
   * Accepts {@code YYYY-MM-DD}, {@code YYYY/MM/DD} optionally followed by a blank or 'T' and a time
   * and an optional zone ({@code Z}, {@code +HH}, {@code +HH:MM}). A date alone gives midnight.
   * Without zone the result is a {@link LocalDateTime}, with zone an {@link OffsetDateTime}.
   */
  public static Object parseDateTime(String text) {
    String t = text.strip();
    if (t.length() < 8) throw new DateTimeParseException("Not a date time", text, 0);
    int split = -1;
    for (int i = 8; i < t.length(); i++) {
      if (t.charAt(i) == ' ' || t.charAt(i) == 'T') {
        split = i;
        break;
      }
    }
    LocalDate d = parseDate(split < 0 ? t : t.substring(0, split));
    if (split < 0) return d.atStartOfDay();
    String rest = t.substring(split + 1).strip();
    java.time.ZoneOffset zone = null;
    int zi = -1;
    for (int i = 1; i < rest.length(); i++) {
      char c = rest.charAt(i);
      if (c == 'Z' || c == '+' || c == '-') {
        zi = i;
        break;
      }
    }
    if (zi >= 0) {
      String z = rest.substring(zi).strip();
      if (!TZ_RE.matcher(z).matches()) throw new DateTimeParseException("Bad time zone", text, 0);
      zone =
          z.equals("Z")
              ? java.time.ZoneOffset.UTC
              : java.time.ZoneOffset.of(
                  z.length() == 3
                      ? z + ":00"
                      : z.length() == 5 ? z.substring(0, 3) + ":" + z.substring(3) : z);
      rest = rest.substring(0, zi).strip();
    }
    var m = TIME_RE.matcher(rest);
    if (!m.matches()) throw new DateTimeParseException("Not a time", text, 0);
    LocalDateTime ldt = LocalDateTime.of(d, time(m));
    return zone == null ? ldt : ldt.atOffset(zone);
  }

  /**
   * Text of a value as GDAL's {@code GetFieldAsString} gives it: dates as {@code YYYY/MM/DD}, date
   * times as {@code YYYY/MM/DD HH:MM:SS[.fff][+TZ]}, reals with 15 significant digits, lists as
   * {@code n:(a,b)}.
   */
  public static String format(FieldDefn defn, Object v) {
    if (v == null) return "";
    return switch (v) {
      case Double d ->
          defn.precision() > 0
              ? String.format(java.util.Locale.ROOT, "%." + defn.precision() + "f", d)
              : dev.jdal.geom.Wkt.number(d);
      case LocalDate d ->
          String.format("%04d/%02d/%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
      case LocalTime t -> timeText(t);
      case LocalDateTime t ->
          String.format("%04d/%02d/%02d ", t.getYear(), t.getMonthValue(), t.getDayOfMonth())
              + timeText(t.toLocalTime());
      case OffsetDateTime o -> format(defn, o.toLocalDateTime()) + zoneText(o.getOffset());
      case byte[] b -> java.util.HexFormat.ofDelimiter("").withUpperCase().formatHex(b);
      case List<?> l -> {
        StringBuilder sb = new StringBuilder().append(l.size()).append(":(");
        for (int i = 0; i < l.size(); i++)
          sb.append(i > 0 ? "," : "").append(format(defn, l.get(i)));
        yield sb.append(')').toString();
      }
      default -> v.toString();
    };
  }

  private static String timeText(LocalTime t) {
    return t.getNano() == 0
        ? String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond())
        : String.format(
            java.util.Locale.ROOT,
            "%02d:%02d:%06.3f",
            t.getHour(),
            t.getMinute(),
            t.getSecond() + t.getNano() / 1e9);
  }

  private static String zoneText(java.time.ZoneOffset o) {
    int total = o.getTotalSeconds();
    char sign = total < 0 ? '-' : '+';
    total = Math.abs(total);
    int h = total / 3600, mi = total % 3600 / 60;
    return mi == 0 ? String.format("%c%02d", sign, h) : String.format("%c%02d%02d", sign, h, mi);
  }

  private static <T> List<T> toList(Object v, java.util.function.Function<Object, T> f) {
    if (!(v instanceof Iterable<?> it)) throw new ClassCastException(v.getClass().getName());
    List<T> out = new ArrayList<>();
    for (Object o : it) out.add(f.apply(o));
    return java.util.Collections.unmodifiableList(out);
  }
}
