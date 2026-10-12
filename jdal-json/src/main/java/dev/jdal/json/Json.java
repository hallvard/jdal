package dev.jdal.json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsing and writing of JSON. The parser is tolerant: it accepts a byte order mark, {@code //} and
 * {@code /* *}{@code /} comments, trailing commas, single quoted strings, {@code NaN}/{@code
 * Infinity}, numbers such as {@code .5} or {@code +1}, raw control characters in strings and
 * unknown escapes, and keeps the last of duplicate member names. The writer is strict: it only
 * produces valid JSON and throws {@link IllegalArgumentException} for what JSON cannot represent
 * (non-finite numbers, lone surrogates).
 */
public final class Json {

  private static final int MAX_DEPTH = 1000;

  private Json() {}

  // ------------------------------------------------------------------ parsing

  /**
   * @throws JsonException if {@code text} is not (tolerated) JSON or has trailing content
   */
  public static JsonValue parse(String text) {
    try (JsonReader r = new JsonReader(new java.io.StringReader(text))) {
      JsonValue v = r.nextValue();
      r.expectEnd();
      return v;
    }
  }

  /**
   * Parses one value from {@code in} (and closes it); see {@link JsonReader} to stream large
   * documents.
   */
  public static JsonValue parse(java.io.Reader in) {
    try (JsonReader r = new JsonReader(in)) {
      JsonValue v = r.nextValue();
      r.expectEnd();
      return v;
    }
  }

  // ------------------------------------------------------------------ writing

  /** Compact, strictly valid JSON. */
  public static String write(JsonValue value) {
    StringBuilder sb = new StringBuilder();
    writeTo(value, sb);
    return sb.toString();
  }

  public static void writeTo(JsonValue value, Appendable out) {
    try {
      put(value, out, 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A string as a JSON string literal including the quotes. */
  public static String quote(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 2);
    try {
      putString(s, sb);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return sb.toString();
  }

  private static void put(JsonValue v, Appendable out, int depth) throws IOException {
    if (depth > MAX_DEPTH) throw new IllegalArgumentException("Nesting deeper than " + MAX_DEPTH);
    switch (v) {
      case JsonNull _ -> out.append("null");
      case JsonBoolean b -> out.append(b.value() ? "true" : "false");
      case JsonNumber n -> out.append(n.canonicalText());
      case JsonString str -> putString(str.value(), out);
      case JsonArray a -> {
        out.append('[');
        boolean first = true;
        for (JsonValue e : a) {
          if (!first) out.append(',');
          first = false;
          put(e, out, depth + 1);
        }
        out.append(']');
      }
      case JsonObject o -> {
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, JsonValue> e : o.members().entrySet()) {
          if (!first) out.append(',');
          first = false;
          putString(e.getKey(), out);
          out.append(':');
          put(e.getValue(), out, depth + 1);
        }
        out.append('}');
      }
    }
  }

  private static void putString(String s, Appendable out) throws IOException {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20 || c == '\u2028' || c == '\u2029') {
            out.append(String.format("\\u%04x", (int) c));
          } else if (Character.isHighSurrogate(c)) {
            if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
              throw new IllegalArgumentException("Cannot write a lone surrogate");
            }
            out.append(c).append(s.charAt(++i));
          } else if (Character.isLowSurrogate(c)) {
            throw new IllegalArgumentException("Cannot write a lone surrogate");
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  // ------------------------------------------------------------------ conversion from Java

  /**
   * Converts Java values: null, {@link JsonValue}, CharSequence, Boolean, integer types,
   * Float/Double (finite), BigInteger/BigDecimal, Map (keys via toString), Iterable and Object[]
   * (elements converted recursively), Character and java.time values (as strings).
   */
  public static JsonValue of(Object o) {
    return switch (o) {
      case null -> JsonNull.INSTANCE;
      case JsonValue v -> v;
      case CharSequence cs -> new JsonString(cs.toString());
      case Character c -> new JsonString(c.toString());
      case Boolean b -> JsonBoolean.of(b);
      case Integer i -> JsonNumber.of(i);
      case Long l -> JsonNumber.of(l);
      case Short sh -> JsonNumber.of(sh);
      case Byte by -> JsonNumber.of(by);
      case Float f -> JsonNumber.of(f.doubleValue());
      case Double d -> JsonNumber.of(d);
      case BigInteger bi -> new JsonNumber(bi.toString());
      case BigDecimal bd -> new JsonNumber(bd.toString());
      case Map<?, ?> m -> {
        Map<String, JsonValue> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), of(v)));
        yield new JsonObject(out);
      }
      case Iterable<?> it -> {
        List<JsonValue> out = new ArrayList<>();
        for (Object e : it) out.add(of(e));
        yield new JsonArray(out);
      }
      case Object[] arr -> {
        List<JsonValue> out = new ArrayList<>();
        for (Object e : arr) out.add(of(e));
        yield new JsonArray(out);
      }
      case java.time.temporal.TemporalAccessor t -> new JsonString(t.toString());
      default ->
          throw new IllegalArgumentException(
              "Cannot convert " + o.getClass().getName() + " to JSON");
    };
  }
}
