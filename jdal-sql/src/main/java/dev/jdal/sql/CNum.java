package dev.jdal.sql;

/**
 * Number parsing with the semantics of C's atoi, strtoll and strtod: prefix parsing, no exceptions.
 */
final class CNum {

  private CNum() {}

  private static int skipSpace(String s) {
    int i = 0;
    while (i < s.length() && (s.charAt(i) == ' ' || (s.charAt(i) >= '\t' && s.charAt(i) <= '\r')))
      i++;
    return i;
  }

  static int atoi(String s) {
    long v = atoll(s);
    return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
  }

  /** Leading integer, saturating on overflow; 0 if there is none. */
  static long atoll(String s) {
    int i = skipSpace(s);
    boolean neg = false;
    if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) neg = s.charAt(i++) == '-';
    long v = 0;
    boolean overflow = false;
    while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
      int d = s.charAt(i++) - '0';
      if (!overflow) {
        if (v > (Long.MAX_VALUE - d) / 10) overflow = true;
        else v = v * 10 + d;
      }
    }
    if (overflow) return neg ? Long.MIN_VALUE : Long.MAX_VALUE;
    return neg ? -v : v;
  }

  /**
   * Length of the longest prefix (after leading blanks) that strtod would consume, and its value.
   */
  record Parsed(double value, int end) {}

  static Parsed strtod(String s) {
    int start = skipSpace(s);
    int i = start;
    if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
    int numStart = i;
    String rest = s.substring(i);
    String lower = rest.toLowerCase(java.util.Locale.ROOT);
    if (lower.startsWith("infinity") || lower.startsWith("inf") || lower.startsWith("nan")) {
      int len = lower.startsWith("infinity") ? 8 : 3;
      double v = lower.startsWith("nan") ? Double.NaN : Double.POSITIVE_INFINITY;
      if (s.charAt(start) == '-') v = -v;
      return new Parsed(v, i + len);
    }
    int digits = 0;
    while (i < s.length() && Character.isDigit(s.charAt(i)) && s.charAt(i) < 128) {
      i++;
      digits++;
    }
    if (i < s.length() && s.charAt(i) == '.') {
      i++;
      while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
        i++;
        digits++;
      }
    }
    if (digits == 0) return new Parsed(0, 0);
    int mantissaEnd = i;
    if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
      int j = i + 1;
      if (j < s.length() && (s.charAt(j) == '-' || s.charAt(j) == '+')) j++;
      int expDigits = 0;
      while (j < s.length() && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
        j++;
        expDigits++;
      }
      if (expDigits > 0) mantissaEnd = j;
    }
    try {
      return new Parsed(Double.parseDouble(s.substring(start, mantissaEnd)), mantissaEnd);
    } catch (NumberFormatException e) {
      return new Parsed(0, 0);
    }
  }

  static double atof(String s) {
    return strtod(s).value();
  }

  /** The value if the whole string (an empty string counts as 0) is a number, else null. */
  static Double parseWhole(String s) {
    if (s.isEmpty()) return 0.0;
    Parsed p = strtod(s);
    return p.end() == s.length() ? p.value() : null;
  }
}
