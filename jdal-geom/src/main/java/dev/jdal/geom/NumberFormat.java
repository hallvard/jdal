package dev.jdal.geom;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Number formatting identical to GDAL's {@code OGRFormatDouble}: values below one in magnitude are
 * written in fixed notation with {@code precision} decimals, others like C's {@code
 * %.<precision>G}; fixed values get "intelligent rounding" of floating point noise.
 */
final class NumberFormat {

  private NumberFormat() {}

  static boolean fitsInt(double d) {
    return d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE && d == (double) (int) d;
  }

  /** X Y (and Z) formatting of {@code OGRMakeWktCoordinate}. */
  static void appendXY(StringBuilder sb, double x, double y, int precision) {
    if (fitsInt(x) && fitsInt(y)) {
      sb.append((int) x).append(' ').append((int) y);
      return;
    }
    String xs = format(x, precision, true);
    if (isInteger(xs)) xs += ".0";
    String ys = format(y, precision, true);
    if (isInteger(ys)) ys += ".0";
    sb.append(xs).append(' ').append(ys);
  }

  /** Z or M formatting. */
  static void appendOrdinate(StringBuilder sb, double v, int precision) {
    if (fitsInt(v)) sb.append((int) v);
    else sb.append(format(v, precision, true));
  }

  private static boolean isInteger(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
    }
    return true;
  }

  static String format(double v, int precision, boolean round) {
    if (Double.isInfinite(v)) return v > 0 ? "inf" : "-inf";
    if (Double.isNaN(v)) return "nan";
    String s;
    if (Math.abs(v) < 1) {
      s = new BigDecimal(v).setScale(precision, RoundingMode.HALF_EVEN).toPlainString();
      if (v < 0 && s.charAt(0) != '-')
        s = "-" + s; // negative zero or underflow keeps its sign, as in C
      if (v == 0 && 1 / v < 0 && s.charAt(0) != '-') s = "-" + s;
      if (round) s = intelliround(s);
    } else {
      s = general(v, precision);
    }
    return removeTrailingZeros(s);
  }

  /** C's {@code %.<p>G}. */
  static String general(double v, int p) {
    if (v == 0) return "0";
    BigDecimal bd = new BigDecimal(v).round(new MathContext(p, RoundingMode.HALF_EVEN));
    int exp = bd.precision() - bd.scale() - 1;
    if (exp < -4 || exp >= p) {
      bd = bd.stripTrailingZeros();
      String digits = bd.unscaledValue().abs().toString();
      StringBuilder s = new StringBuilder();
      if (bd.signum() < 0) s.append('-');
      s.append(digits.charAt(0));
      if (digits.length() > 1) s.append('.').append(digits, 1, digits.length());
      s.append('E').append(exp < 0 ? '-' : '+');
      int ae = Math.abs(exp);
      if (ae < 10) s.append('0');
      s.append(ae);
      return s.toString();
    }
    return bd.stripTrailingZeros().toPlainString();
  }

  /** Removes trailing zeros but keeps one digit after the decimal point. */
  static String removeTrailingZeros(String s) {
    int dot = s.indexOf('.');
    if (dot < 0) return s;
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == '0') end--;
    s = s.substring(0, end);
    if (s.endsWith(".")) s += "0";
    return s;
  }

  private static String roundup(String in) {
    boolean negative = in.startsWith("-");
    char[] c = (negative ? in.substring(1) : in).toCharArray();
    String prefix = "";
    for (int pos = c.length - 1; pos >= 0; pos--) {
      if (c[pos] == '.') continue;
      c[pos]++;
      if (c[pos] != ':') break;
      c[pos] = '0';
      if (pos == 0) prefix = "1";
    }
    return (negative ? "-" : "") + prefix + new String(c);
  }

  /**
   * Port of GDAL's {@code intelliround}: removes likely binary-to-decimal noise at the end of the
   * digits.
   */
  static String intelliround(String s) {
    int len = s.length();
    if (len <= 10) return s;
    int dot = s.indexOf('.');
    if (dot < 0) return s;
    if (s.indexOf('e', dot) >= 0 || s.indexOf('E', dot) >= 0) return s;
    int before = dot - 1;
    if (s.charAt(0) == '-') before--;

    if (at(s, len - 2) == '0'
        && at(s, len - 3) == '0'
        && at(s, len - 4) == '0'
        && at(s, len - 5) == '0'
        && at(s, len - 6) == '0') {
      return s.substring(0, len - 1);
    } else if (dot < len - 8
        && (before >= 4 || at(s, len - 3) == '0')
        && (before >= 5 || at(s, len - 4) == '0')
        && (before >= 6 || at(s, len - 5) == '0')
        && (before >= 7 || at(s, len - 6) == '0')
        && (before >= 8 || at(s, len - 7) == '0')
        && at(s, len - 8) == '0'
        && at(s, len - 9) == '0') {
      return s.substring(0, len - 8);
    } else if (at(s, len - 2) == '9'
        && at(s, len - 3) == '9'
        && at(s, len - 4) == '9'
        && at(s, len - 5) == '9'
        && at(s, len - 6) == '9') {
      return roundup(s.substring(0, len - 6));
    } else if (dot < len - 9
        && (before >= 4 || at(s, len - 3) == '9')
        && (before >= 5 || at(s, len - 4) == '9')
        && (before >= 6 || at(s, len - 5) == '9')
        && (before >= 7 || at(s, len - 6) == '9')
        && (before >= 8 || at(s, len - 7) == '9')
        && at(s, len - 8) == '9'
        && at(s, len - 9) == '9') {
      return roundup(s.substring(0, len - 9));
    }
    return s;
  }

  private static char at(String s, int i) {
    return s.charAt(i);
  }
}
