package dev.jdal.sql;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** String helpers of the SQL engine: LIKE, hstore lookup, dates, all on Unicode code points. */
final class Text {

  private Text() {}

  // ------------------------------------------------------------------ LIKE

  /**
   * GDAL's {@code swq_test_like}: {@code %} any run, {@code _} any one character, optional escape.
   */
  static boolean like(String input, String pattern, int escape, boolean insensitive) {
    int[] in = input.codePoints().toArray();
    int[] pat = pattern.codePoints().toArray();
    return like(in, 0, pat, 0, escape, insensitive);
  }

  private static boolean like(int[] in, int ii, int[] pat, int pi, int esc, boolean insensitive) {
    while (ii < in.length) {
      if (pi >= pat.length) return false;
      int p = pat[pi];
      if (esc != 0 && p == esc) {
        pi++;
        if (pi >= pat.length) return false;
        if (pat[pi] != in[ii]) return false;
        ii++;
        pi++;
      } else if (p == '_') {
        pi++;
        ii++;
      } else if (p == '%') {
        if (pi + 1 >= pat.length) return true;
        for (int eat = 0; ii + eat < in.length; eat++) {
          if (like(in, ii + eat, pat, pi + 1, esc, insensitive)) return true;
        }
        return false;
      } else {
        int a = in[ii];
        if (a != p && !(insensitive && foldEquals(a, p))) return false;
        ii++;
        pi++;
      }
    }
    // input consumed: the rest of the pattern must be nothing or a single %
    return pi >= pat.length || (pat.length - pi == 1 && pat[pi] == '%');
  }

  private static boolean foldEquals(int a, int b) {
    return Character.toUpperCase(a) == Character.toUpperCase(b)
        || Character.toLowerCase(a) == Character.toLowerCase(b);
  }

  // ------------------------------------------------------------------ hstore

  private record Next(int next, String value) {}

  private static final Next FAIL = new Next(-1, null);

  private static int checkEnd(String s, int it, boolean isKey) {
    it++;
    for (; it < s.length(); it++) {
      char ch = s.charAt(it);
      if (isKey) {
        if (ch == ' ') continue;
        if (ch == '=' && it + 1 < s.length() && s.charAt(it + 1) == '>') return it + 2;
        return -1;
      }
      if (ch == ' ') continue;
      if (ch == ',') return it + 1;
      return -1;
    }
    return it;
  }

  private static Next nextString(String s, int it, boolean isKey) {
    boolean inString = false;
    int outStart = -1;
    StringBuilder quoted = null;
    for (; it < s.length(); it++) {
      char ch = s.charAt(it);
      if (inString) {
        if (ch == '"') {
          int end = checkEnd(s, it, isKey);
          return end < 0 && false ? FAIL : new Next(end, quoted.toString());
        } else if (ch == '\\') {
          it++;
          if (it >= s.length()) return FAIL;
          ch = s.charAt(it);
        }
        quoted.append(ch);
      } else if (ch == ' ') {
        if (outStart != -1) return new Next(checkEnd(s, it, isKey), s.substring(outStart, it));
      } else if (isKey && ch == '=' && it + 1 < s.length() && s.charAt(it + 1) == '>') {
        if (outStart != -1) return new Next(it + 2, s.substring(outStart, it));
      } else if (!isKey && ch == ',') {
        if (outStart != -1) return new Next(it + 1, s.substring(outStart, it));
      } else if (ch == '"') {
        outStart = it + 1;
        quoted = new StringBuilder();
        inString = true;
      } else if (outStart == -1) {
        outStart = it;
      }
    }
    if (!inString && outStart != -1) return new Next(s.length(), s.substring(outStart));
    return FAIL;
  }

  /** The value of {@code key} in an hstore text such as {@code a=>b, "c d"=>"e"}, or null. */
  static String hstoreValue(String hstore, String key) {
    int it = 0;
    while (true) {
      Next k = nextString(hstore, it, true);
      if (k.next() < 0 || k.next() >= hstore.length()) return null;
      Next v = nextString(hstore, k.next(), false);
      if (v.next() < 0) return null;
      if (k.value().equals(key)) return v.value();
      if (v.next() >= hstore.length()) return null;
      it = v.next();
    }
  }

  // ------------------------------------------------------------------ dates

  private static final Pattern DATE_TIME =
      Pattern.compile(
          "^ *(?:(-?\\d{1,5})[-/](\\d{2})[-/](\\d{2}))?(?:[ ]*T?["
              + " ]*(\\d{2}):(\\d{2})(?::(\\d{2}(?:[.,]\\d+)?))?)?["
              + " ]*(?:Z|[+-]\\d{2}(?::?\\d{2})?)?$");

  /**
   * Year, month, day, hour, minute, second of a date, time or date time text (zone ignored), or
   * null.
   */
  static double[] parseDate(String text) {
    Matcher m = DATE_TIME.matcher(text);
    if (!m.matches() || text.isBlank()) return null;
    double[] r = new double[6];
    if (m.group(1) != null) {
      int year = Integer.parseInt(m.group(1));
      if (year < 100 && year >= 0 && m.group(1).length() <= 2) year += year >= 30 ? 1900 : 2000;
      int mo = Integer.parseInt(m.group(2)), d = Integer.parseInt(m.group(3));
      if (mo == 0 || mo > 12 || d == 0 || d > 31) return null;
      r[0] = year;
      r[1] = mo;
      r[2] = d;
    } else if (m.group(4) == null) {
      return null;
    }
    if (m.group(4) != null) {
      int h = Integer.parseInt(m.group(4)), mi = Integer.parseInt(m.group(5));
      if (h > 23 || mi > 59) return null;
      r[3] = h;
      r[4] = mi;
      if (m.group(6) != null) r[5] = Double.parseDouble(m.group(6).replace(',', '.'));
    }
    return r;
  }

  /** OGRCompareDate: field by field, the time zone is ignored. */
  static int compareDate(double[] a, double[] b) {
    for (int i = 0; i < 6; i++) {
      int c = Double.compare(a[i], b[i]);
      if (c != 0) return c;
    }
    return 0;
  }

  /** C's {@code strcasecmp} for Unicode text. */
  static int compareIgnoreCase(String a, String b) {
    return a.compareToIgnoreCase(b);
  }
}
