package dev.jdal.json;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A number kept as its source text so that integers and big values keep their precision. Numbers
 * read from tolerant input may be non-standard (".5", "+1", "NaN"); the writer only emits strictly
 * valid numbers.
 */
public record JsonNumber(String text) implements JsonValue {

  public JsonNumber {
    Objects.requireNonNull(text, "text");
    if (text.isEmpty()) throw new IllegalArgumentException("empty number");
  }

  public static JsonNumber of(long v) {
    return new JsonNumber(Long.toString(v));
  }

  public static JsonNumber of(int v) {
    return new JsonNumber(Integer.toString(v));
  }

  /**
   * @throws IllegalArgumentException for NaN and infinities, which JSON cannot represent
   */
  public static JsonNumber of(double v) {
    if (!Double.isFinite(v)) throw new IllegalArgumentException("JSON cannot represent " + v);
    return new JsonNumber(Double.toString(v));
  }

  /** True for numbers written without fraction or exponent. */
  public boolean isIntegral() {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '.' || c == 'e' || c == 'E' || Character.isLetter(c)) return false;
    }
    return true;
  }

  public boolean canConvertToLong() {
    if (!isIntegral()) return false;
    try {
      Long.parseLong(stripPlus());
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  public boolean canConvertToInt() {
    if (!isIntegral()) return false;
    try {
      Integer.parseInt(stripPlus());
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  public int intValue() {
    return (int) doubleOrLong();
  }

  public long longValue() {
    return isIntegral() && canConvertToLong() ? Long.parseLong(stripPlus()) : (long) doubleValue();
  }

  public double doubleValue() {
    return switch (text) {
      case "NaN" -> Double.NaN;
      case "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY;
      case "-Infinity" -> Double.NEGATIVE_INFINITY;
      default -> Double.parseDouble(text);
    };
  }

  private double doubleOrLong() {
    return canConvertToInt() ? Integer.parseInt(stripPlus()) : doubleValue();
  }

  /**
   * An {@link Integer}, {@link Long} or {@link Double}, whichever is the smallest that holds the
   * value.
   */
  public Number toNumber() {
    if (canConvertToInt()) return Integer.parseInt(stripPlus());
    if (canConvertToLong()) return Long.parseLong(stripPlus());
    return doubleValue();
  }

  private String stripPlus() {
    return text.startsWith("+") ? text.substring(1) : text;
  }

  /** The text as strictly valid JSON, or throws if the number has no JSON representation. */
  String canonicalText() {
    if (isStrictNumber(text)) return text;
    try {
      return new BigDecimal(text.startsWith("+") ? text.substring(1) : text).toString();
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("JSON cannot represent number '" + text + "'");
    }
  }

  private static boolean isStrictNumber(String s) {
    int i = 0, n = s.length();
    if (i < n && s.charAt(i) == '-') i++;
    if (i >= n) return false;
    if (s.charAt(i) == '0') i++;
    else if (s.charAt(i) >= '1' && s.charAt(i) <= '9')
      while (i < n && Character.isDigit(s.charAt(i))) i++;
    else return false;
    if (i < n && s.charAt(i) == '.') {
      int st = ++i;
      while (i < n && Character.isDigit(s.charAt(i))) i++;
      if (i == st) return false;
    }
    if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
      i++;
      if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
      int st = i;
      while (i < n && Character.isDigit(s.charAt(i))) i++;
      if (i == st) return false;
    }
    return i == n;
  }

  @Override
  public String toString() {
    return text;
  }
}
