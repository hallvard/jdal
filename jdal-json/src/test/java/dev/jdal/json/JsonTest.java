package dev.jdal.json;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

  @Test
  void parsesStandardJson() {
    JsonValue v =
        Json.parse("{\"a\": [1, 2.5, -3e2, true, false, null, \"x\"], \"b\": {\"c\": \"d\"}}");
    assertEquals(1, ((JsonNumber) v.path("a").path(0)).intValue());
    assertEquals(2.5, ((JsonNumber) v.path("a").path(1)).doubleValue());
    assertEquals(-300.0, ((JsonNumber) v.path("a").path(2)).doubleValue());
    assertEquals(JsonBoolean.TRUE, v.path("a").path(3));
    assertTrue(v.path("a").path(5).isNull());
    assertEquals("d", v.path("b").path("c").asString(null));
    assertTrue(v.path("missing").isNull());
    assertTrue(v.path("a").path(99).isNull());
  }

  @Test
  void toleratesNonStandardInput() {
    String text =
        "\uFEFF// header\n"
            + "{ /* c */ 'a': [1, 2, ], \"b\": .5, \"c\": +7, \"d\": NaN, \"e\": -Infinity,\n"
            + "\"s\": \"raw\ttab \\q \\u00e6\", \"dup\": 1, \"dup\": 2, }  // end\n";
    JsonValue v = Json.parse(text);
    assertEquals(2, ((JsonArray) v.path("a")).size());
    assertEquals(0.5, ((JsonNumber) v.path("b")).doubleValue());
    assertEquals(7, ((JsonNumber) v.path("c")).longValue());
    assertTrue(Double.isNaN(((JsonNumber) v.path("d")).doubleValue()));
    assertEquals(Double.NEGATIVE_INFINITY, ((JsonNumber) v.path("e")).doubleValue());
    assertEquals("raw\ttab q æ", v.path("s").asString(null));
    assertEquals(2, ((JsonNumber) v.path("dup")).intValue());
  }

  @Test
  void rejectsBrokenInput() {
    for (String bad :
        List.of(
            "",
            "{",
            "[1,",
            "{\"a\"}",
            "{\"a\":}",
            "[1 2]",
            "tru",
            "\"abc",
            "{\"a\":1} x",
            "/* open",
            "[1.2.3]",
            "--1",
            "{1:2}",
            "\"\\u12\"",
            "e5")) {
      assertThrows(JsonException.class, () -> Json.parse(bad), bad);
    }
    JsonException e = assertThrows(JsonException.class, () -> Json.parse("{\n  \"a\": ?\n}"));
    assertTrue(e.getMessage().contains("line 2"), e.getMessage());
  }

  @Test
  void streamsLargeArraysElementByElement() {
    StringBuilder sb = new StringBuilder("{\"skip\": {\"x\": [1,2,3]}, \"items\": [");
    for (int i = 0; i < 20000; i++)
      sb.append(i == 0 ? "" : ",").append("{\"i\":").append(i).append('}');
    sb.append("], \"after\": 'done', }");
    try (JsonReader r = new JsonReader(new java.io.StringReader(sb.toString()))) {
      assertEquals(JsonReader.Token.BEGIN_OBJECT, r.peek());
      r.beginObject();
      long sum = 0;
      int n = 0;
      String after = null;
      while (r.hasNext()) {
        String name = r.nextName();
        if (name.equals("items")) {
          r.beginArray();
          while (r.hasNext()) {
            sum += ((JsonNumber) r.nextValue().path("i")).longValue();
            n++;
          }
          r.endArray();
        } else if (name.equals("after")) {
          after = r.nextString();
        } else {
          r.skipValue();
        }
      }
      r.endObject();
      r.expectEnd();
      assertEquals(20000, n);
      assertEquals(19999L * 20000 / 2, sum);
      assertEquals("done", after);
    }
  }

  @Test
  void streamingReportsMisuseAndErrors() {
    JsonReader r = new JsonReader(new java.io.StringReader("[1 2]"));
    r.beginArray();
    assertTrue(r.hasNext());
    r.nextValue();
    assertThrows(JsonException.class, r::hasNext);
    assertThrows(
        JsonException.class, () -> new JsonReader(new java.io.StringReader("{")).nextName());
    JsonReader multi = new JsonReader(new java.io.StringReader("1 \"two\" [3]"));
    int count = 0;
    while (multi.hasNext()) {
      multi.nextValue();
      count++;
    }
    assertEquals(3, count);
  }

  @Test
  void limitsNesting() {
    assertThrows(JsonException.class, () -> Json.parse("[".repeat(5000) + "]".repeat(5000)));
    assertDoesNotThrow(() -> Json.parse("[".repeat(500) + "]".repeat(500)));
  }

  @Test
  void numbersKeepPrecision() {
    JsonNumber big = (JsonNumber) Json.parse("12345678901234567890");
    assertFalse(big.canConvertToLong());
    assertEquals("12345678901234567890", Json.write(big));
    JsonNumber l = (JsonNumber) Json.parse("5000000000");
    assertTrue(l.isIntegral());
    assertFalse(l.canConvertToInt());
    assertEquals(5_000_000_000L, l.toNumber());
    assertEquals(7, Json.parse("7").path("x").isNull() ? 7 : 0);
    assertEquals(1.0, ((JsonNumber) Json.parse("1.0")).toNumber());
  }

  @Test
  void writesStrictJson() {
    JsonObject o =
        JsonObject.builder()
            .put("s", "a\"b\\c\n\u0001\u2028é\uD83D\uDE00")
            .put("n", 1.5)
            .put("l", 3L)
            .put("t", true)
            .put("z", (Object) null)
            .put("list", List.of(1, "x", List.of()))
            .put("m", Map.of("k", 1))
            .build();
    assertEquals(
        "{\"s\":\"a\\\"b\\\\c\\n"
            + "\\u0001\\u2028é\uD83D\uDE00\",\"n\":1.5,\"l\":3,\"t\":true,\"z\":null,"
            + "\"list\":[1,\"x\",[]],\"m\":{\"k\":1}}",
        Json.write(o));
    assertEquals(o, Json.parse(Json.write(o)));
  }

  @Test
  void writerNormalizesTolerantNumbersAndRejectsUnrepresentable() {
    assertEquals("[0.5,7,1e2]", Json.write(Json.parse("[.5, +7, 1e2]")));
    assertThrows(IllegalArgumentException.class, () -> Json.write(Json.parse("NaN")));
    assertThrows(IllegalArgumentException.class, () -> Json.write(Json.parse("[Infinity]")));
    assertThrows(IllegalArgumentException.class, () -> JsonNumber.of(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> Json.write(new JsonString("\uD800")));
    assertThrows(IllegalArgumentException.class, () -> Json.of(new Object()));
    assertEquals("\"\\n\"", Json.quote("\n"));
  }
}
