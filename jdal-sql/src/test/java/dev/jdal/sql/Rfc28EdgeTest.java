package dev.jdal.sql;

import static dev.jdal.sql.SqlTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.*;
import dev.jdal.feature.*;
import dev.jdal.testkit.Port;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Dates, integer overflows, long OR/AND chains, ordering by two columns and IN on dates (end of
 * ogr_sql_rfc28.py).
 */
class Rfc28EdgeTest {

  static Dataset mem() {
    return Jdal.create("Memory", "mem", Map.of());
  }

  static Layer layer(Dataset d, String name, FieldDefn... fields) {
    return d.createLayer(new FeatureType(name, List.of(fields), List.of()), Map.of());
  }

  static long count(Layer l, String where) {
    return l.count(Query.sql(where));
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_48")
  @Test
  void dateTimeComparisons() {
    Layer lyr = layer(mem(), "lyr", FieldDefn.of("dt", FieldType.DATETIME));
    lyr.insert(Feature.builder(lyr.type()).set("dt", "2017/02/17 11:06:34").build());
    assertThrows(JdalException.class, () -> lyr.features(Query.sql("dt >= 2500000000")));
    // GDAL (without exceptions) logs an error and matches nothing; here an unparsable date is an
    // exception
    for (String bad : List.of("dt >= 'a'", "'a' <= dt", "dt BETWEEN dt AND 'a'")) {
      assertTrue(
          assertThrows(JdalException.class, () -> count(lyr, bad))
              .getMessage()
              .contains("Failed to parse date 'a'"),
          bad);
    }
    String[][] cases = {
      {"dt >= '2017/02/17 11:06:34'", "1"},
      {"dt >= '2017/02/17'", "1"},
      {"dt >= '2017/02/17 11:06:35'", "0"},
      {"dt > '2017/02/17 11:06:33'", "1"},
      {"dt > '2017/02/17 11:06:34'", "0"},
      {"dt <= '2017/02/17 11:06:34'", "1"},
      {"dt <= '2017/02/17 11:06:33'", "0"},
      {"dt < '2017/02/17 11:06:35'", "1"},
      {"dt < '2017/02/17 11:06:34'", "0"},
      {"dt = '2017/02/17 11:06:34'", "1"},
      {"dt = '2017/02/17 11:06:34.001'", "0"},
      {"dt BETWEEN dt AND dt", "1"},
      {"dt BETWEEN '2017/02/17 11:06:33.999' AND '2017/02/17 11:06:34.001'", "1"},
      {"dt BETWEEN '2017/02/17 11:06:34.001' AND dt", "0"},
      {"dt BETWEEN dt AND '2017/02/17 11:06:33.999'", "0"}
    };
    for (String[] c : cases) assertEquals(Long.parseLong(c[1]), count(lyr, c[0]), c[0]);
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_datetime_null")
  @Test
  void nullDateTimes() {
    Layer lyr = layer(mem(), "lyr", FieldDefn.of("dt", FieldType.DATETIME));
    lyr.insert(Feature.builder(lyr.type()).build());
    lyr.insert(Feature.builder(lyr.type()).set("dt", "2017/02/17 11:06:34").build());
    List<String> w =
        Diagnostics.capture(
            () -> {
              assertEquals(1, count(lyr, "dt = '2017/02/17 11:06:34'"));
              assertEquals(1, count(lyr, "dt IS NULL"));
            });
    assertEquals(List.of(), w);
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_int_overflows")
  @Test
  void integerOverflows() {
    Dataset m = mem();
    Layer lyr = layer(m, "lyr");
    lyr.insert(Feature.builder(lyr.type()).build());
    Object[][] tests = {
      {"SELECT -9223372036854775808 FROM lyr", Long.MIN_VALUE},
      {"SELECT -(-9223372036854775808) FROM lyr", Long.MIN_VALUE},
      {"SELECT -9223372036854775808/1 FROM lyr", Long.MIN_VALUE},
      {"SELECT 9223372036854775807 FROM lyr", Long.MAX_VALUE},
      {"SELECT 9223372036854775807*1 FROM lyr", Long.MAX_VALUE},
      {"SELECT 9223372036854775807/1 FROM lyr", Long.MAX_VALUE},
      {"SELECT 9223372036854775807/-1 FROM lyr", -Long.MAX_VALUE},
      {"SELECT 9223372036854775807*-1 FROM lyr", -Long.MAX_VALUE},
      {"SELECT -1*9223372036854775807 FROM lyr", -Long.MAX_VALUE},
      {"SELECT 1*(-9223372036854775808) FROM lyr", Long.MIN_VALUE},
      {"SELECT 0*(-9223372036854775808) FROM lyr", 0L},
      {"SELECT 9223372036854775806+1 FROM lyr", Long.MAX_VALUE},
      {"SELECT -9223372036854775807-1 FROM lyr", Long.MIN_VALUE},
      {"SELECT 9223372036854775808 FROM lyr", 9223372036854775808.0},
      {"SELECT -9223372036854775809 FROM lyr", -9223372036854775809.0},
      {"SELECT 9223372036854775807+1 FROM lyr", null},
      {"SELECT 9223372036854775807 - (-1) FROM lyr", null},
      {"SELECT -9223372036854775808-1 FROM lyr", null},
      {"SELECT -9223372036854775808 + (-1) FROM lyr", null},
      {"SELECT 9223372036854775807*2 FROM lyr", null},
      {"SELECT -9223372036854775807*2 FROM lyr", null},
      {"SELECT -1*(-9223372036854775808) FROM lyr", null},
      {"SELECT 2 * (-9223372036854775807) FROM lyr", null},
      {"SELECT 9223372036854775807*-2 FROM lyr", null},
      {"SELECT -9223372036854775807*-2 FROM lyr", null},
      {"SELECT -9223372036854775808*-1 FROM lyr", null},
      {"SELECT -9223372036854775808/-1 FROM lyr", null}, {"SELECT 1/0 FROM lyr", 2147483647L}
    };
    for (Object[] t : tests) {
      try (Layer l = m.executeSql((String) t[0])) {
        Object got = Diagnostics.capture(() -> {}).isEmpty() ? all(l).getFirst().get(0) : null;
        if (t[1] == null) assertNull(got, (String) t[0]);
        else if (t[1] instanceof Double d)
          assertEquals(d, ((Number) got).doubleValue(), (String) t[0]);
        else assertEquals(((Number) t[1]).longValue(), ((Number) got).longValue(), (String) t[0]);
      }
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_many_or",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_many_and"
  })
  @Test
  void longChains() {
    Layer lyr = layer(mem(), "lyr", FieldDefn.of("val", FieldType.INTEGER));
    lyr.insert(Feature.builder(lyr.type()).set("val", -15).build());
    StringBuilder ors = new StringBuilder("1 = 1 AND (");
    for (int i = 0; i < 1024; i++) ors.append(i > 0 ? " OR " : "").append("val = ").append(i);
    assertEquals(0, count(lyr, ors + ")"));
    ors = new StringBuilder("1 = 1 AND (");
    for (int i = 0; i < 1024; i++) ors.append(i > 0 ? " OR " : "").append("val = ").append(i - 100);
    assertEquals(1, count(lyr, ors + ")"));
    for (int v : new int[] {-1, -15}) {
      StringBuilder ands = new StringBuilder("1 = 1 AND (");
      for (int i = 0; i < 1024; i++) ands.append(i > 0 ? " AND " : "").append("val = ").append(v);
      assertEquals(v == -15 ? 1 : 0, count(lyr, ands + ")"));
    }
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_nested_or")
  @Test
  void nestedOr() {
    Layer lyr = layer(mem(), "test", FieldDefn.of("fclass", FieldType.STRING));
    lyr.insert(Feature.builder(lyr.type()).set(0, "x").build());
    for (String ok :
        List.of(
            "(fclass = 'a' OR fclass = 'b') OR (fclass = 'c' OR fclass = 'd' OR fclass = 'x')",
            "(fclass = 'c' OR fclass = 'd' OR fclass = 'x') OR (fclass = 'a' OR fclass = 'b')",
            "fclass = 'c' OR fclass = 'd' OR fclass = 'x'",
            "fclass = 'c' OR (fclass = 'd' OR fclass = 'x')",
            "(fclass = 'c' OR fclass = 'd') OR fclass = 'x'",
            "fclass = 'x' OR fclass = 'c' OR fclass = 'd'",
            "fclass = 'x' OR (fclass = 'c' OR fclass = 'd')",
            "(fclass = 'x' OR fclass = 'd') OR fclass = 'd'",
            "(fclass = 'a' OR fclass = 'b' OR fclass = 'b2') OR (fclass = 'c' OR fclass = 'd' OR"
                + " fclass = 'x')",
            "(1 = 0) OR (fclass = 'c' OR fclass = 'd' OR fclass = 'x')",
            "(fclass = 'c' OR fclass = 'd' OR fclass = 'x') OR (1 = 0)",
            "(1 = 0 OR 1 = 0 OR 1 = 1) AND (fclass = 'c' OR fclass = 'd' OR fclass = 'x')")) {
      assertEquals(1, count(lyr, ok), ok);
      assertEquals(0, count(lyr, ok.replace("'x'", "'COND_NOT_MET'")), ok);
    }
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_order_by_two_columns")
  @Test
  void orderByTwoColumns() {
    Dataset m = mem();
    Layer lyr =
        layer(
            m,
            "lyr",
            FieldDefn.of("int_val", FieldType.INTEGER),
            FieldDefn.of("str_val", FieldType.STRING));
    for (int i = 0; i < 101; i++) {
      String s =
          i < 26
              ? String.valueOf((char) ('a' + i))
              : "" + (char) ('a' + i / 26) + (char) ('a' + i % 26);
      lyr.insert(Feature.builder(lyr.type()).set("int_val", 100 - i).set("str_val", s).build());
    }
    try (Layer l = m.executeSql("SELECT * FROM lyr ORDER BY int_val, str_val")) {
      List<Feature> fs = all(l);
      for (int i = 0; i < 101; i++) assertEquals(i, fs.get(i).get("int_val"));
    }
    try (Layer l = m.executeSql("SELECT * FROM lyr ORDER BY int_val, str_val LIMIT 1")) {
      assertEquals(0, all(l).getFirst().get("int_val"));
    }
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_in_date_filter")
  @Test
  void inOnDates() {
    Layer lyr =
        layer(
            mem(),
            "ogr_in_date_filter",
            FieldDefn.of("date_minus", FieldType.DATE),
            FieldDefn.of("date_slash", FieldType.DATE));
    lyr.insert(Feature.builder(lyr.type()).set(0, "1950-12-31").set(1, "1950/12/31").build());
    lyr.insert(Feature.builder(lyr.type()).set(0, "1960-12-31").set(1, "1960/12/31").build());
    assertEquals(2, lyr.count());
    Object[][] cases = {
      {"date_minus IN ('1960-12-31')", new long[] {1}},
      {"date_minus IN ('1960-12-31', '1950-12-31')", new long[] {0, 1}},
      {"date_slash IN ('1960/12/31')", new long[] {1}},
      {"date_slash IN ('1960/12/31', '1950/12/31')", new long[] {0, 1}},
      {"date_slash IN ('1960-12-31')", new long[] {1}},
      {"date_slash IN ('1960-12-31', '1950-12-31')", new long[] {0, 1}},
      {"date_minus IN ('1960/12/31')", new long[] {1}},
      {"date_minus IN ('1960/12/31', '1950/12/31')", new long[] {0, 1}},
      {"date_minus IN ('2020/12/31', '2020/12/31')", new long[] {}},
      {"date_slash IN ('2020-12-31', '2020-12-31')", new long[] {}}
    };
    for (Object[] c : cases) {
      try (Stream<Feature> s = lyr.features(Query.sql((String) c[0]))) {
        List<Long> fids = s.map(Feature::fid).toList();
        List<Long> expected = new ArrayList<>();
        for (long v : (long[]) c[1]) expected.add(v);
        assertEquals(expected, fids, (String) c[0]);
      }
    }
  }
}
