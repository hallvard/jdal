package dev.jdal.json;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pull parser over a {@link Reader} that never holds more than the current value in memory. It is
 * tolerant in the way described at {@link Json}. Typical use for a large array:
 *
 * <pre>{@code
 * r.beginArray();
 * while (r.hasNext()) { JsonValue element = r.nextValue(); ... }
 * r.endArray();
 * }</pre>
 *
 * Objects are read with {@code beginObject()}, then {@code while (hasNext()) { name = nextName();
 * <value> }}.
 */
public final class JsonReader implements Closeable {

  public enum Token {
    BEGIN_OBJECT,
    BEGIN_ARRAY,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
    END_DOCUMENT
  }

  private static final int MAX_DEPTH = 1000;
  private static final String TOKEN_CHARS =
      "+-.0123456789eEabcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

  private final Reader in;
  private final char[] buf = new char[8192];
  private int len, pos;
  private boolean eof, first = true;
  private int line = 1, col = 1;

  private int depth;
  private boolean[] isObject = new boolean[32];
  private boolean[] needComma = new boolean[32];

  public JsonReader(Reader in) {
    this.in = in;
  }

  // ------------------------------------------------------------------ characters

  private int peekChar() {
    if (pos >= len) {
      if (eof) return -1;
      try {
        len = in.read(buf, 0, buf.length);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      pos = 0;
      if (len <= 0) {
        len = 0;
        eof = true;
        return -1;
      }
      if (first) {
        first = false;
        if (buf[0] == '﻿') pos = 1;
        return peekChar();
      }
    }
    return buf[pos];
  }

  private int readChar() {
    int c = peekChar();
    if (c < 0) return c;
    pos++;
    if (c == '\n') {
      line++;
      col = 1;
    } else {
      col++;
    }
    return c;
  }

  private JsonException error(String msg) {
    return new JsonException(msg + " at line " + line + ", column " + col);
  }

  private void skipBlank() {
    while (true) {
      int c = peekChar();
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ' ' || c == '﻿') {
        readChar();
      } else if (c == '/') {
        readChar();
        int d = readChar();
        if (d == '/') {
          while ((d = peekChar()) >= 0 && d != '\n') readChar();
        } else if (d == '*') {
          int prev = 0;
          while (true) {
            int e = readChar();
            if (e < 0) throw error("Unterminated comment");
            if (prev == '*' && e == '/') break;
            prev = e;
          }
        } else {
          throw error("Unexpected character '/'");
        }
      } else {
        return;
      }
    }
  }

  // ------------------------------------------------------------------ pull API

  /** The kind of the next value, without consuming it. */
  public Token peek() {
    skipBlank();
    int c = peekChar();
    return switch (c) {
      case -1 -> Token.END_DOCUMENT;
      case '{' -> Token.BEGIN_OBJECT;
      case '[' -> Token.BEGIN_ARRAY;
      case '"', '\'' -> Token.STRING;
      case 't', 'f' -> Token.BOOLEAN;
      case 'n' -> Token.NULL;
      case '+', '-', '.', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'N', 'I' ->
          Token.NUMBER;
      default -> throw error("Unexpected character '" + (char) c + "'");
    };
  }

  public void beginObject() {
    begin('{', true);
  }

  public void beginArray() {
    begin('[', false);
  }

  private void begin(char open, boolean object) {
    skipBlank();
    if (peekChar() != open) throw error("Expected '" + open + "'");
    if (depth >= MAX_DEPTH) throw error("Nesting deeper than " + MAX_DEPTH);
    readChar();
    if (depth == isObject.length) {
      isObject = Arrays.copyOf(isObject, depth * 2);
      needComma = Arrays.copyOf(needComma, depth * 2);
    }
    isObject[depth] = object;
    needComma[depth] = false;
    depth++;
  }

  public void endObject() {
    end('}', true);
  }

  public void endArray() {
    end(']', false);
  }

  private void end(char close, boolean object) {
    if (depth == 0 || isObject[depth - 1] != object)
      throw error("Not inside " + (object ? "an object" : "an array"));
    if (hasNext()) throw error("Expected '" + close + "'");
    readChar();
    depth--;
    valueDone();
  }

  /**
   * True if the current object or array has another member/element; at top level, if the document
   * has more values.
   */
  public boolean hasNext() {
    skipBlank();
    int c = peekChar();
    if (depth == 0) return c != -1;
    boolean object = isObject[depth - 1];
    char close = object ? '}' : ']';
    if (c == -1) throw error("Unterminated " + (object ? "object" : "array"));
    if (needComma[depth - 1]) {
      if (c == ',') {
        readChar();
        needComma[depth - 1] = false;
        skipBlank();
        c = peekChar();
        if (c == -1) throw error("Unterminated " + (object ? "object" : "array"));
      } else if (c != close) {
        throw error("Expected ',' or '" + close + "'");
      }
    }
    return c != close;
  }

  /** Reads the next member name of the current object and the following ':'. */
  public String nextName() {
    if (depth == 0 || !isObject[depth - 1]) throw error("Not inside an object");
    if (!hasNext()) throw error("No more members");
    int c = peekChar();
    if (c != '"' && c != '\'') throw error("Expected a member name");
    String name = readString();
    skipBlank();
    if (peekChar() != ':') throw error("Expected ':'");
    readChar();
    return name;
  }

  /** Reads a complete value (of any size) into memory. */
  public JsonValue nextValue() {
    skipBlank();
    JsonValue v = readValue(depth);
    valueDone();
    return v;
  }

  public void skipValue() {
    nextValue();
  }

  public String nextString() {
    if (!(nextValue() instanceof JsonString s)) throw error("Expected a string");
    return s.value();
  }

  /** Throws if anything but blanks and comments follows. */
  public void expectEnd() {
    skipBlank();
    if (peekChar() != -1) throw error("Unexpected content after the JSON value");
  }

  @Override
  public void close() {
    try {
      in.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void valueDone() {
    if (depth > 0) needComma[depth - 1] = true;
  }

  // ------------------------------------------------------------------ values

  private JsonValue readValue(int level) {
    if (level > MAX_DEPTH) throw error("Nesting deeper than " + MAX_DEPTH);
    int c = peekChar();
    if (c < 0) throw error("Unexpected end of input");
    return switch (c) {
      case '{' -> readObject(level);
      case '[' -> readArray(level);
      case '"', '\'' -> new JsonString(readString());
      default -> readLiteralOrNumber();
    };
  }

  private JsonValue readObject(int level) {
    readChar();
    Map<String, JsonValue> members = new LinkedHashMap<>();
    boolean needSep = false;
    while (true) {
      skipBlank();
      int c = peekChar();
      if (c < 0) throw error("Unterminated object");
      if (c == '}') {
        readChar();
        return new JsonObject(members);
      }
      if (needSep) {
        if (c != ',') throw error("Expected ',' or '}'");
        readChar();
        skipBlank();
        c = peekChar();
        if (c == '}') continue;
      }
      if (c != '"' && c != '\'') throw error("Expected a member name");
      String key = readString();
      skipBlank();
      if (peekChar() != ':') throw error("Expected ':'");
      readChar();
      skipBlank();
      members.put(key, readValue(level + 1));
      needSep = true;
    }
  }

  private JsonValue readArray(int level) {
    readChar();
    List<JsonValue> elements = new ArrayList<>();
    boolean needSep = false;
    while (true) {
      skipBlank();
      int c = peekChar();
      if (c < 0) throw error("Unterminated array");
      if (c == ']') {
        readChar();
        return new JsonArray(elements);
      }
      if (needSep) {
        if (c != ',') throw error("Expected ',' or ']'");
        readChar();
        skipBlank();
        if (peekChar() == ']') continue;
      }
      elements.add(readValue(level + 1));
      needSep = true;
    }
  }

  private String readString() {
    int quote = readChar();
    StringBuilder sb = new StringBuilder();
    while (true) {
      int c = readChar();
      if (c < 0) throw error("Unterminated string");
      if (c == quote) return sb.toString();
      if (c != '\\') {
        sb.append((char) c);
        continue;
      }
      int e = readChar();
      if (e < 0) throw error("Unterminated string");
      switch (e) {
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 't' -> sb.append('\t');
        case 'u' -> {
          int v = 0;
          for (int i = 0; i < 4; i++) {
            int h = Character.digit(peekChar(), 16);
            if (h < 0) throw error("Bad \\u escape");
            readChar();
            v = v * 16 + h;
          }
          sb.append((char) v);
        }
        default -> sb.append((char) e); // \" \\ \/ and, tolerated, any other escaped character
      }
    }
  }

  private JsonValue readLiteralOrNumber() {
    StringBuilder sb = new StringBuilder();
    int c;
    while ((c = peekChar()) >= 0 && TOKEN_CHARS.indexOf(c) >= 0) {
      sb.append((char) c);
      readChar();
    }
    String tok = sb.toString();
    switch (tok) {
      case "true":
        return JsonBoolean.TRUE;
      case "false":
        return JsonBoolean.FALSE;
      case "null":
        return JsonNull.INSTANCE;
      case "NaN":
      case "Infinity":
      case "+Infinity":
      case "-Infinity":
        return new JsonNumber(tok);
      default:
        break;
    }
    if (tok.isEmpty()) throw error("Unexpected character '" + (char) peekChar() + "'");
    if (!isTolerantNumber(tok)) throw error("Invalid token '" + tok + "'");
    return new JsonNumber(tok);
  }

  /** [+-]? (digits [. digits*] | . digits) ([eE][+-]?digits)? */
  private static boolean isTolerantNumber(String t) {
    int i = 0, n = t.length();
    if (i < n && (t.charAt(i) == '+' || t.charAt(i) == '-')) i++;
    int digits = 0;
    while (i < n && Character.isDigit(t.charAt(i))) {
      i++;
      digits++;
    }
    if (i < n && t.charAt(i) == '.') {
      i++;
      while (i < n && Character.isDigit(t.charAt(i))) {
        i++;
        digits++;
      }
    }
    if (digits == 0) return false;
    if (i < n && (t.charAt(i) == 'e' || t.charAt(i) == 'E')) {
      i++;
      if (i < n && (t.charAt(i) == '+' || t.charAt(i) == '-')) i++;
      int ed = 0;
      while (i < n && Character.isDigit(t.charAt(i))) {
        i++;
        ed++;
      }
      if (ed == 0) return false;
    }
    return i == n;
  }
}
