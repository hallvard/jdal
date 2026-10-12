package dev.jdal.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Tokenizer for OGR SQL: SQL92 quoting, case-insensitive keywords, C-like numbers. */
final class Lexer {

  enum Kind {
    INTEGER,
    FLOAT,
    STRING,
    IDENTIFIER,
    KEYWORD,
    SYMBOL,
    EOF
  }

  record Token(Kind kind, String text, Val value, boolean quoted, int pos) {
    boolean isKeyword(String k) {
      return kind == Kind.KEYWORD && text.equals(k);
    }

    boolean isSymbol(String s) {
      return kind == Kind.SYMBOL && text.equals(s);
    }

    String describe() {
      return switch (kind) {
        case EOF -> "end of string";
        case INTEGER -> "integer number";
        case FLOAT -> "floating point number";
        case STRING -> "string";
        case IDENTIFIER -> "identifier";
        default -> text;
      };
    }
  }

  private static final Set<String> KEYWORDS =
      Set.of(
          "IN",
          "LIKE",
          "ILIKE",
          "ESCAPE",
          "EXCEPT",
          "EXCLUDE",
          "NULL",
          "IS",
          "NOT",
          "AND",
          "OR",
          "BETWEEN",
          "SELECT",
          "LEFT",
          "JOIN",
          "WHERE",
          "ON",
          "ORDER",
          "BY",
          "FROM",
          "AS",
          "ASC",
          "DESC",
          "DISTINCT",
          "CAST",
          "UNION",
          "ALL",
          "LIMIT",
          "OFFSET",
          "HIDDEN",
          "OUTER",
          "INNER");

  private Lexer() {}

  static List<Token> tokenize(String sql) {
    List<Token> out = new ArrayList<>();
    int i = 0, n = sql.length();
    while (true) {
      while (i < n
          && (sql.charAt(i) == ' '
              || sql.charAt(i) == '\t'
              || sql.charAt(i) == '\n'
              || sql.charAt(i) == '\r')) i++;
      if (i >= n) {
        out.add(new Token(Kind.EOF, "", null, false, n));
        return out;
      }
      char c = sql.charAt(i);
      int start = i;
      if (c == '"' || c == '\'') {
        StringBuilder sb = new StringBuilder();
        i++;
        boolean closed = false;
        while (i < n) {
          char d = sql.charAt(i);
          if (c == '"' && d == '\\') {
            i++;
            if (i >= n) break;
          } else if (c == '\'' && d == '\'' && i + 1 < n && sql.charAt(i + 1) == '\'') {
            i++;
          } else if (d == c) {
            i++;
            closed = true;
            break;
          }
          sb.append(sql.charAt(i++));
        }
        if (!closed) throw new dev.jdal.core.JdalException("Did not find end-of-string character");
        out.add(
            c == '"'
                ? new Token(Kind.IDENTIFIER, sb.toString(), null, true, start)
                : new Token(Kind.STRING, sb.toString(), Val.string(sb.toString()), true, start));
      } else if (c >= '0' && c <= '9') {
        while (i < n && sql.charAt(i) >= '0' && sql.charAt(i) <= '9') i++;
        if (i < n && sql.charAt(i) == '.') {
          i++;
          while (i < n && sql.charAt(i) >= '0' && sql.charAt(i) <= '9') i++;
        }
        if (i < n && (sql.charAt(i) == 'e' || sql.charAt(i) == 'E')) {
          i++;
          if (i < n && (sql.charAt(i) == '-' || sql.charAt(i) == '+')) i++;
          while (i < n && sql.charAt(i) >= '0' && sql.charAt(i) <= '9') i++;
        }
        String t = sql.substring(start, i);
        if (t.contains(".") || t.contains("e") || t.contains("E")) {
          out.add(new Token(Kind.FLOAT, t, Val.real(CNum.atof(t)), false, start));
        } else if (t.length() > 19
            || (t.length() >= 19 && t.compareTo("9223372036854775807") > 0)) {
          // too big for 64 bit integers: a float; 2^63 is kept as text for "-9223372036854775808"
          out.add(new Token(Kind.FLOAT, t, Val.real(CNum.atof(t)), false, start));
        } else {
          out.add(new Token(Kind.INTEGER, t, Val.integer(Long.parseLong(t)), false, start));
        }
      } else if (Character.isLetter(c)) {
        while (i < n
            && (Character.isLetterOrDigit(sql.charAt(i))
                || sql.charAt(i) == '_'
                || sql.charAt(i) > 127)) i++;
        String t = sql.substring(start, i);
        String up = t.toUpperCase(Locale.ROOT);
        out.add(
            KEYWORDS.contains(up)
                ? new Token(Kind.KEYWORD, up, null, false, start)
                : new Token(Kind.IDENTIFIER, t, null, false, start));
      } else {
        i++;
        out.add(new Token(Kind.SYMBOL, String.valueOf(c), null, false, start));
      }
    }
  }
}
