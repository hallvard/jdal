package dev.jdal.sql;

import static dev.jdal.sql.SqlTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.*;
import dev.jdal.feature.*;
import dev.jdal.testkit.Port;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Port of the second half of ogr_sql_rfc28.py. */
class Rfc28MoreTest {

  Dataset ds;

  @BeforeEach
  void openData() {
    ds = dataDs();
  }

  @AfterEach
  void close() {
    ds.close();
  }

  static Dataset mem() {
    return Jdal.create("Memory", "mem", Map.of());
  }

  static Layer layer(Dataset d, String name, FieldDefn... fields) {
    return d.createLayer(new FeatureType(name, List.of(fields), List.of()), Map.of());
  }

  static Object first(Layer l, String field) {
    return all(l).getFirst().get(field);
  }

  @Port({
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_25",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_26",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_27"
  })
  @Test
  void escapeNegativeSubstrAndFloatConstants() {
    try (Layer l = ds.executeSql("select * from poly where prfedea LIKE 'x35043408' ESCAPE 'x'")) {
      assertEquals(1, l.count());
    }
    try (Layer l = ds.executeSql("SELECT SUBSTR(PRFEDEA,-2) from poly where eas_id in (168,179)")) {
      check(l, "substr_prfedea", "11", "23");
    }
    try (Layer l = ds.executeSql("SELECT * FROM poly WHERE 4000000000. > 2000000000.")) {
      assertEquals(10, l.count());
    }
  }

  private Object scalar(String formula) {
    try (Layer l = ds.executeSql("SELECT " + formula + " from poly where fid = 0")) {
      return all(l).getFirst().get(0);
    }
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_28")
  @Test
  void arithmeticAndLogicalOperators() {
    String[][] operands = {
      {"6", "3"},
      {"5.1", "3.2"},
      {"5", "3.2"},
      {"5.1", "3"},
      {"3000000000000", "3"},
      {"3", "3000000000000"},
      {"3000000000000", "3."},
      {"3.", "3000000000000"}
    };
    for (String op : List.of("+", "-", "*", "/", "%")) {
      for (String[] o : operands) {
        if (op.equals("/") && o[0].equals("3") && o[1].startsWith("3000")) continue;
        if (op.equals("/") && o[0].equals("3.") && o[1].startsWith("3000")) continue;
        double a = Double.parseDouble(o[0]), b = Double.parseDouble(o[1]);
        double expected =
            switch (op) {
              case "+" -> a + b;
              case "-" -> a - b;
              case "*" -> a * b;
              case "/" -> a / b;
              default -> a % b;
            };
        Object got = scalar(o[0] + op + o[1]);
        assertEquals(
            expected,
            ((Number) got).doubleValue(),
            Math.abs(expected) * 1e-9 + 1e-9,
            o[0] + op + o[1]);
      }
    }
    String[][] cmp = {
      {"3", "3"},
      {"3.", "3."},
      {"3", "6"},
      {"3.", "6."},
      {"3", "6."},
      {"3.", "6"},
      {"6", "3"},
      {"6.", "3."},
      {"6", "3."},
      {"6.", "3"},
      {"'a'", "'a'"},
      {"'a'", "'b'"},
      {"'b'", "'a'"},
      {"3", "1000000000000"},
      {"1000000000000", "3"},
      {"1000000000000", "1000000000000"}
    };
    for (String op : List.of("<", "<=", ">", ">=", " = ", "<>")) {
      for (String[] o : cmp) {
        int c =
            o[0].startsWith("'")
                ? o[0].compareTo(o[1])
                : Double.compare(Double.parseDouble(o[0]), Double.parseDouble(o[1]));
        boolean expected =
            switch (op.strip()) {
              case "<" -> c < 0;
              case "<=" -> c <= 0;
              case ">" -> c > 0;
              case ">=" -> c >= 0;
              case "=" -> c == 0;
              default -> c != 0;
            };
        assertEquals(
            expected ? 1 : 0, ((Number) scalar(o[0] + op + o[1])).intValue(), o[0] + op + o[1]);
      }
    }
    Object[][] formulas = {
      {"3 in (3,5)", true},
      {"1000000000000 in (1000000000000, 1000000000001)", true},
      {"4 in (3,5)", false},
      {"3. in (3.,4.)", true},
      {"4. in (3.,5.)", false},
      {"'c' in ('c','e')", true},
      {"'d' in ('c','e')", false},
      {"2 between 2 and 4", true},
      {"3 between 2 and 4", true},
      {"4 between 2 and 4", true},
      {"1 between 2 and 4", false},
      {"5 between 2 and 4", false},
      {"2. between 2. and 4.", true},
      {"3. between 2. and 4.", true},
      {"4. between 2. and 4.", true},
      {"1. between 2. and 4.", false},
      {"5. between 2. and 4.", false},
      {"'b' between 'b' and 'd'", true},
      {"'c' between 'b' and 'd'", true},
      {"'d' between 'b' and 'd'", true},
      {"'a' between 'b' and 'd'", false},
      {"'e' between 'b' and 'd'", false},
      {"null is null", true},
      {"1 is null", false},
      {"1.0 is null", false}
    };
    for (Object[] f : formulas) {
      assertEquals(
          (Boolean) f[1] ? 1 : 0, ((Number) scalar((String) f[0])).intValue(), (String) f[0]);
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_29",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_30"
  })
  @Test
  void nullOperands() {
    try (Layer l =
        ds.executeSql(
            "select * from idlink where (eas_id + cast(null as integer)) is not null or eas_id ="
                + " 170 + cast(null as integer) or (eas_id + cast(null as float)) is not null or"
                + " eas_id = 170.0 + cast(null as float)")) {
      assertEquals(0, l.count());
    }
    try (Layer l =
        ds.executeSql(
            "select * from idlink2 where F1 <> 'foo' or concat(F1,cast(null as character(32))) is"
                + " not null")) {
      assertEquals(0, l.count());
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_31",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_32",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_union_all_three_branch_and"
  })
  @Test
  void unionAll() {
    try (Layer l = ds.executeSql("select * from idlink union all select * from idlink2")) {
      assertNotEquals(6 + 7, l.count());
    }
    try (Layer l =
        ds.executeSql("(select * from idlink) union all (select * from idlink2 order by eas_id)")) {
      assertNotEquals(6 + 7, l.count());
    }
    try (Layer l =
        ds.executeSql(
            "select * from idlink where 1=1 and 1=1 and 1=0 union all select * from idlink2 where"
                + " 1=1 and 1=1 and 1=0")) {
      assertEquals(0, l.count());
    }
    try (Layer l =
        ds.executeSql(
            "select * from idlink where 1=1 and 1=1 and 1=1 union all select * from idlink2 where"
                + " 1=1 and 1=1 and 1=1")) {
      assertEquals(7 + 7, l.count());
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_33",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_34",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_35",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_35_bis",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_35_ter",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_38"
  })
  @Test
  void invalidStatements() {
    assertTrue(
        assertThrows(JdalException.class, () -> ds.executeSql("select * from idlink'"))
            .getMessage()
            .contains("Did not find end-of-string character"));
    assertTrue(
        assertThrows(JdalException.class, () -> ds.executeSql("select foo.* from idlink"))
            .getMessage()
            .startsWith("Table foo not recognised from foo.* definition"));
    assertTrue(
        assertThrows(
                JdalException.class,
                () -> ds.executeSql("select distinct eas_id, distinct name from idlink"))
            .getMessage()
            .startsWith("SQL Expression Parsing Error"));
    assertTrue(
        assertThrows(
                JdalException.class,
                () -> ds.executeSql("select distinct eas_id, name from idlink"))
            .getMessage()
            .startsWith("SELECT DISTINCT not supported on multiple columns"));
    assertTrue(
        assertThrows(JdalException.class, () -> ds.executeSql("select distinct * from idlink"))
            .getMessage()
            .startsWith("SELECT DISTINCT not supported on multiple columns"));
    assertTrue(
        assertThrows(JdalException.class, () -> ds.executeSql("SELECT SUBSTR(PRFEDEA) from poly"))
            .getMessage()
            .startsWith("Expected 2 or 3 arguments to SUBSTR(), but got 1"));
    assertTrue(
        assertThrows(JdalException.class, () -> ds.executeSql("SELECT SUBSTR(1,2) from poly"))
            .getMessage()
            .startsWith("Wrong argument type for SUBSTR()"));
  }

  @Port({
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_36",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_37"
  })
  @Test
  void distinctOrderByRestrictions() {
    try (Layer l = ds.executeSql("select distinct eas_id from idlink order by eas_id, name")) {
      assertTrue(
          assertThrows(JdalException.class, () -> all(l))
              .getMessage()
              .startsWith("Can't ORDER BY a DISTINCT list by more than one key"));
    }
    try (Layer l = ds.executeSql("select distinct eas_id from idlink order by name")) {
      assertTrue(
          assertThrows(JdalException.class, () -> all(l))
              .getMessage()
              .startsWith("Only selected DISTINCT field can be used for ORDER BY"));
    }
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_39")
  @Test
  void countOnNoRows() {
    try (Layer l = ds.executeSql("SELECT COUNT(*) from poly where 0 = 1")) {
      check(l, "count_*", 0);
    }
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_40")
  @Test
  void aggregatesOnDates() {
    Dataset m = mem();
    Layer lyr = layer(m, "test", FieldDefn.of("DATE", FieldType.DATETIME));
    lyr.insert(Feature.builder(lyr.type()).set(0, "2013/12/31 23:59:59").build());
    lyr.insert(Feature.builder(lyr.type()).set(0, "2013/01/01 00:00:00").build());
    try (Layer l =
        m.executeSql(
            "SELECT MIN(DATE), MAX(DATE), AVG(DATE), STDDEV_POP(DATE), STDDEV_SAMP(DATE) from"
                + " test")) {
      Feature f = all(l).getFirst();
      FeatureType t = l.type();
      assertEquals("2013/01/01 00:00:00", FieldValues.format(t.fields().get(0), f.get("MIN_DATE")));
      assertEquals("2013/12/31 23:59:59", FieldValues.format(t.fields().get(1), f.get("MAX_DATE")));
      assertEquals(
          "2013/07/02 11:59:59.500", FieldValues.format(t.fields().get(2), f.get("AVG_DATE")));
      assertEquals(15767999.5, (Double) f.get("STDDEV_POP_DATE"), 15767999.5 * 1e-15);
      assertEquals(
          22299318.744392183, (Double) f.get("STDDEV_SAMP_DATE"), 22299318.744392183 * 1e-15);
    }
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_41")
  @Test
  void fieldWithDot() {
    Dataset m = mem();
    Layer lyr = layer(m, "my_layer", FieldDefn.of("a.b", FieldType.INTEGER));
    lyr.insert(Feature.builder(lyr.type()).set(0, 2).build());
    try (Layer l = m.executeSql("select * from my_layer")) {
      assertEquals(2, first(l, "a.b"));
    }
    try (Layer l = m.executeSql("select l.* from my_layer l")) {
      assertEquals(2, first(l, "l.a.b"));
    }
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_42")
  @Test
  void booleanAndInt16() {
    Dataset m = mem();
    Layer lyr =
        layer(
            m,
            "test",
            FieldDefn.of("b", FieldType.INTEGER, FieldSubType.BOOLEAN),
            FieldDefn.of("short", FieldType.INTEGER, FieldSubType.INT16));
    lyr.insert(Feature.builder(lyr.type()).set(0, 0).set(1, 32000).build());
    lyr.insert(Feature.builder(lyr.type()).set(0, 1).build());
    try (Layer l =
        m.executeSql(
            "SELECT b, NOT b, 1 + b, CAST(1 AS BOOLEAN), b IS NOT NULL, short, 1 + short, CAST(1 +"
                + " short as SMALLINT) FROM test WHERE b OR NOT b")) {
      FieldSubType[] subs = {
        FieldSubType.BOOLEAN,
        FieldSubType.BOOLEAN,
        FieldSubType.NONE,
        FieldSubType.BOOLEAN,
        FieldSubType.BOOLEAN,
        FieldSubType.INT16,
        FieldSubType.NONE,
        FieldSubType.INT16
      };
      for (int i = 0; i < subs.length; i++)
        assertEquals(subs[i], l.type().fields().get(i).subType(), "field " + i);
      List<Feature> fs = all(l);
      assertEquals(List.of(0, 1, 1, 1, 1, 32000, 32001, 32001), fs.get(0).values());
      assertEquals(1, fs.get(1).get(0));
      assertEquals(0, fs.get(1).get(1));
      assertEquals(2, fs.get(1).get(2));
    }
    try (Layer l = m.executeSql("SELECT MIN(b), MAX(b), SUM(b) FROM test")) {
      assertEquals(FieldSubType.BOOLEAN, l.type().fields().get(0).subType());
      assertEquals(FieldSubType.BOOLEAN, l.type().fields().get(1).subType());
      assertEquals(FieldSubType.NONE, l.type().fields().get(2).subType());
      Feature f = all(l).getFirst();
      assertEquals(0, f.get("MIN_b"));
      assertEquals(1, f.get("MAX_b"));
      assertEquals(1, f.get("SUM_b"));
    }
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_43")
  @Test
  void integer64() {
    Dataset m = mem();
    Layer lyr = layer(m, "test", FieldDefn.of("myint64", FieldType.INTEGER64));
    lyr.insert(Feature.builder(lyr.type()).set(0, -1000000000000L).build());
    lyr.insert(Feature.builder(lyr.type()).set(0, 100000000000L).build());
    try (Layer l =
        m.executeSql(
            "SELECT 1000000000000, myint64, CAST(1 AS bigint), CAST(100000000000 AS bigint), CAST(1"
                + " AS numeric(15,0)) FROM test WHERE myint64 < -9999999999 or myint64 >"
                + " 9999999999")) {
      Feature f = all(l).getFirst();
      assertEquals(FieldType.INTEGER64, l.type().fields().get(2).type());
      assertEquals(FieldType.INTEGER64, l.type().fields().get(3).type());
      assertEquals(List.of(1000000000000L, -1000000000000L, 1L, 100000000000L, 1L), f.values());
    }
    try (Layer l = m.executeSql("SELECT MIN(myint64), MAX(myint64), SUM(myint64) FROM test")) {
      Feature f = all(l).getFirst();
      assertEquals(-1000000000000L, f.get("MIN_myint64"));
      assertEquals(100000000000L, f.get("MAX_myint64"));
      assertEquals(-1000000000000L + 100000000000L, f.get("SUM_myint64"));
    }
    try (Layer l = m.executeSql("SELECT DISTINCT myint64 FROM test ORDER BY myint64")) {
      check(l, "myint64", -1000000000000L, 100000000000L);
    }
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_44")
  @Test
  void crazyQuoting() {
    Dataset m = mem();
    Layer lyr =
        layer(
            m,
            "lyr.withpoint",
            FieldDefn.of("field.withpoint", FieldType.INTEGER),
            FieldDefn.of("foo", FieldType.INTEGER));
    lyr.insert(Feature.builder(lyr.type()).set(0, -1).build());
    lyr.insert(Feature.builder(lyr.type()).set(0, 1).set(1, 2).build());
    List<String> w =
        Diagnostics.capture(
            () -> {
              try (Layer l =
                  m.executeSql("SELECT * FROM \"lyr.withpoint\" WHERE \"field.withpoint\" = 1")) {
                assertEquals(1, l.count());
              }
              try (Layer l =
                  m.executeSql(
                      "SELECT \"lyr.withpoint\".\"field.withpoint\", \"field.withpoint\" FROM"
                          + " \"lyr.withpoint\" WHERE \"lyr.withpoint\".\"field.withpoint\" = 1")) {
                assertEquals(1, l.count());
              }
            });
    assertEquals(List.of(), w);
    w =
        Diagnostics.capture(
            () -> {
              try (Layer l =
                  m.executeSql("SELECT * FROM \"lyr.withpoint\" WHERE field.withpoint = 1")) {
                assertEquals(1, l.count());
              }
            });
    assertEquals(
        List.of(
            "Passed field name field.withpoint should have been surrounded by double quotes."
                + " Accepted since there is no ambiguity..."),
        w);
    layer(m, "field", FieldDefn.of("id", FieldType.INTEGER));
    assertEquals(
        "\"field\".\"withpoint\" not recognised as an available field.",
        assertThrows(
                JdalException.class,
                () ->
                    m.executeSql(
                        "SELECT * FROM \"lyr.withpoint\" JOIN field ON \"lyr.withpoint\".foo ="
                            + " field.id WHERE field.withpoint = 1"))
            .getMessage());
    w =
        Diagnostics.capture(
            () -> {
              try (Layer l =
                  m.executeSql("SELECT * FROM \"lyr.withpoint\" f WHERE \"f.foo\" = 2")) {
                assertEquals(1, l.count());
              }
            });
    assertEquals(
        List.of(
            "Passed field name f.foo should NOT have been surrounded by double quotes. Accepted"
                + " since there is no ambiguity..."),
        w);
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_45")
  @Test
  void tableAlias() {
    try (Layer l = ds.executeSql("select eas_id from idlink as il where il.eas_id = 166")) {
      assertEquals(1, l.count());
    }
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_46")
  @Test
  void fid64() {
    Dataset m = mem();
    Layer lyr = layer(m, "lyr", FieldDefn.of("val", FieldType.INTEGER));
    for (long[] fv : new long[][] {{3000000000L, 1}, {2500000000L, 2}, {2200000000L, 3}}) {
      lyr.insert(Feature.builder(lyr.type()).fid(fv[0]).set("val", (int) fv[1]).build());
    }
    assertEquals(2, lyr.count(Query.sql("fid >= 2500000000")));
    try (Layer l =
        m.executeSql(
            "SELECT CAST(fid AS bigint) AS outfid, val FROM lyr WHERE fid >= 2500000000 ORDER BY"
                + " fid")) {
      List<Feature> fs = all(l);
      assertEquals(2, fs.size());
      assertEquals(2500000000L, fs.get(0).fid());
      assertEquals(2500000000L, fs.get(0).get("outfid"));
      assertEquals(2, fs.get(0).get("val"));
      assertEquals(3000000000L, fs.get(1).fid());
    }
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_47")
  @Test
  void limitAndOffset() {
    record Case(String sql, Object... eas) {}
    List<Case> cases =
        List.of(
            new Case("SELECT * FROM POLY LIMIT 0"),
            new Case("SELECT * FROM POLY LIMIT 1", 168),
            new Case("SELECT * FROM POLY ORDER BY EAS_ID LIMIT 1", 158),
            new Case("SELECT * FROM POLY WHERE 0 ORDER BY EAS_ID LIMIT 1"),
            new Case("SELECT * FROM POLY WHERE EAS_ID = 168 LIMIT 11", 168),
            new Case("SELECT * FROM POLY WHERE EAS_ID = 168 OFFSET 0", 168),
            new Case("SELECT * FROM POLY WHERE EAS_ID = 168 OFFSET 1"),
            new Case("SELECT * FROM POLY OFFSET 10"),
            new Case("SELECT * FROM POLY OFFSET 8", 165, 170),
            new Case("SELECT * FROM POLY LIMIT 1 OFFSET 8", 165),
            new Case("SELECT * FROM POLY ORDER BY EAS_ID DESC LIMIT 2", 179, 173),
            new Case("SELECT * FROM POLY ORDER BY EAS_ID DESC LIMIT 1 OFFSET 1", 173),
            new Case(
                "SELECT DISTINCT EAS_ID FROM POLY ORDER BY EAS_ID DESC LIMIT 2 OFFSET 3",
                171,
                170));
    for (Case c : cases) {
      try (Layer l = ds.executeSql(c.sql())) {
        assertEquals(c.eas().length, l.count(), c.sql());
        check(l, "EAS_ID", c.eas());
      }
    }
    try (Layer l = ds.executeSql("SELECT * FROM POLY ORDER BY PRFEDEA LIMIT 1")) {
      check(l, "PRFEDEA", "35043369");
    }
  }
}
