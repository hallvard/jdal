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

/** Port of ogr_sql_rfc28.py: SQL expressions in attribute filters and SELECT. */
class Rfc28Test {

  Dataset ds;
  Layer poly;

  @BeforeEach
  void openData() {
    ds = dataDs();
    poly = ds.requireLayer("poly");
  }

  @AfterEach
  void close() {
    ds.close();
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_1")
  @Test
  void arithmeticInFilter() {
    assertEquals(3, countWhere(poly, "160+7 > eas_id"));
  }

  @Port({
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_2",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_3"
  })
  @Test
  void concat() {
    assertEquals(1, countWhere(poly, "CONCAT('x',PRFEDEA) = 'x35043423'"));
    assertEquals(1, countWhere(poly, "'x'+PRFEDEA = 'x35043423'"));
  }

  @Port({
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_4",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_6"
  })
  @Test
  void moduloAndQuotedField() {
    assertEquals(2, countWhere(poly, "EAS_ID % 5 = 1"));
    assertEquals(1, countWhere(poly, "\"EAS_ID\" = 166"));
  }

  @Port({
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_7_wrong_quoting",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_7_good_quoting",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_8_wrong_quoting",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_8_good_quoting"
  })
  @Test
  void qualifiedAndWronglyQuotedNames() {
    List<String> warnings =
        Diagnostics.capture(
            () -> {
              try (Layer ql =
                  ds.executeSql("select eas_id from idlink where \"idlink.eas_id\" = 166")) {
                assertEquals(1, ql.count());
              }
              try (Layer ql =
                  ds.executeSql(
                      "select \"idlink.eas_id\" from idlink where \"idlink.eas_id\" = 166")) {
                assertEquals(1, ql.count());
                check(ql, "idlink.eas_id", 166);
              }
            });
    assertTrue(
        warnings.stream().anyMatch(w -> w.contains("NOT have been surrounded by double quotes")),
        warnings.toString());
    try (Layer ql = ds.executeSql("select eas_id from idlink where idlink.eas_id = 166")) {
      assertEquals(1, ql.count());
    }
    try (Layer ql = ds.executeSql("select idlink.eas_id from idlink where idlink.eas_id = 166")) {
      assertEquals(1, ql.count());
      check(ql, "idlink.eas_id", 166);
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_9",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_10",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_11"
  })
  @Test
  void funkyColumnNames() {
    try (Dataset csv = open("csv/oddname.csv")) {
      Layer lyr = csv.layers().getFirst();
      assertEquals(1, countWhere(lyr, "\"Funky @Name\" = '32'"));
      try (Layer r = csv.executeSql("SELECT * from oddname where \"Funky @Name\" = '32'")) {
        assertEquals(1, r.count());
        check(r, "PRIME_MERIDIAN_CODE", "8902");
      }
      try (Layer r =
          csv.executeSql(
              "SELECT \"Funky @Name\" from oddname where prime_meridian_code = '8902'")) {
        assertEquals(1, r.count());
        check(r, "Funky @Name", "32");
      }
    }
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_12")
  @Test
  void fixedStringFields() {
    try (Layer l =
        ds.executeSql(
            "SELECT 'constant string', 'other' as abc, eas_id from idlink where eas_id = 165")) {
      assertEquals(1, l.count());
      check(l, "abc", "other");
      check(l, "eas_id", 165);
      check(l, "field_1", "constant string");
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_13",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_14",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_15"
  })
  @Test
  void substrAndConcat() {
    assertEquals(1, countWhere(poly, "SUBSTR(PRFEDEA,5,4) = '3423'"));
    try (Layer l =
        ds.executeSql("SELECT SUBSTR(PRFEDEA,4,5) from poly where eas_id in (168,179)")) {
      check(l, "substr_prfedea", "43411", "43423");
    }
    try (Layer l =
        ds.executeSql(
            "SELECT CONCAT(PRFEDEA,' ',CAST(EAS_ID AS CHARACTER(3))) from poly where eas_id in"
                + " (168,179)")) {
      check(l, "concat_prfedea", "35043411 168", "35043423 179");
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_16",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_17"
  })
  @Test
  void negativeNumbersAndDivision() {
    try (Layer l = ds.executeSql("SELECT -1, 3--1,3*-1,2e-1,3-1 from poly where eas_id = 168")) {
      check(l, "field_1", -1);
      check(l, "field_2", 4);
      check(l, "field_3", -3);
      check(l, "field_4", 0.2);
      check(l, "field_5", 2);
    }
    try (Layer l =
        ds.executeSql("SELECT 5/2, 5.0/2.0, 5/2.0, 5.0/2 from poly where eas_id = 168")) {
      check(l, "field_1", 2);
      check(l, "field_2", 2.5);
      check(l, "field_3", 2.5);
      check(l, "field_4", 2.5);
    }
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_18")
  @Test
  void countDistinct() {
    try (Dataset d = open("shp/departs.shp");
        Layer l =
            d.executeSql("SELECT COUNT(distinct id), COUNT(distinct id) as \"xx\" from departs")) {
      check(l, "COUNT_id", 1);
      check(l, "xx", 1);
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_19",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_21",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_22",
    "autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_23"
  })
  @Test
  void notInBetweenLike() {
    try (Layer l = ds.executeSql("select * from poly where eas_id not in (158,165)")) {
      assertEquals(8, l.count());
    }
    long between, geLe, notBetween, notGeLe, notLike1, notLike2;
    try (Layer l = ds.executeSql("select * from poly where eas_id between 165 and 169")) {
      between = l.count();
    }
    try (Layer l = ds.executeSql("select * from poly where eas_id >= 165 and eas_id <= 169")) {
      geLe = l.count();
    }
    assertEquals(geLe, between);
    try (Layer l = ds.executeSql("select * from poly where eas_id not between 165 and 169")) {
      notBetween = l.count();
    }
    try (Layer l = ds.executeSql("select * from poly where not(eas_id >= 165 and eas_id <= 169)")) {
      notGeLe = l.count();
    }
    assertEquals(notGeLe, notBetween);
    try (Layer l = ds.executeSql("select * from poly where PRFEDEA NOT LIKE '35043413'")) {
      notLike1 = l.count();
    }
    try (Layer l = ds.executeSql("select * from poly where NOT (PRFEDEA LIKE '35043413')")) {
      notLike2 = l.count();
    }
    assertEquals(notLike2, notLike1);
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_20")
  @Test
  void precedenceAndUnaryMinus() {
    Dataset mem = Jdal.create("Memory", "my_ds", Map.of());
    Layer lyr =
        mem.createLayer(
            new FeatureType(
                "my_layer", List.of(FieldDefn.of("intfield", FieldType.INTEGER)), List.of()),
            Map.of());
    lyr.insert(Feature.builder(lyr.type()).set(0, 2).build());
    try (Layer l = mem.executeSql("select -intfield + 1 + 2 * 3 + 5 - 3 * 2 from my_layer")) {
      check(l, "FIELD_1", 4);
    }
  }

  @Port("autotest/ogr/ogr_sql_rfc28.py::test_ogr_rfc28_24")
  @Test
  void nullLiterals() {
    try (Layer l =
        ds.executeSql(
            "select *, NULL, NULL as nullstrfield, CAST(null as integer) as nullintfield from poly"
                + " where NULL IS NULL")) {
      Feature f = all(l).getFirst();
      assertNull(f.get("FIELD_4"));
      assertNull(f.get("nullstrfield"));
      assertNull(f.get("nullintfield"));
      assertEquals(10, l.count());
    }
  }
}
