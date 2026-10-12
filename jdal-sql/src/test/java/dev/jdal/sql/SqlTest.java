package dev.jdal.sql;

import static dev.jdal.sql.SqlTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.*;
import dev.jdal.feature.*;
import dev.jdal.geom.*;
import dev.jdal.testkit.Port;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Port of ogr_sql_test.py, the first half: SELECT forms, special fields, DISTINCT, ORDER BY,
 * aggregates.
 */
class SqlTest {

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
    return d.createLayer(
        new FeatureType(
            name,
            List.of(fields),
            List.of(new GeomFieldDefn("", GeometryType.UNKNOWN, Dimension.XY, null, true))),
        Map.of());
  }

  static Layer layerNoGeom(Dataset d, String name, FieldDefn... fields) {
    return d.createLayer(new FeatureType(name, List.of(fields), List.of()), Map.of());
  }

  @Port("autotest/ogr/ogr_sql_test.py::test_ogr_sql_execute_sql")
  @Test
  void executeSqlOnShapefile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
      throws Exception {
    for (String ext : List.of("shp", "shx", "dbf", "prj")) {
      java.nio.file.Files.copy(
          dev.jdal.testkit.Autotest.data("ogr/data/poly." + ext.replace("prj", "PRJ")),
          tmp.resolve("test_ogr_sql_execute_sql." + ext));
    }
    try (Dataset d = Jdal.open(tmp.resolve("test_ogr_sql_execute_sql.shp").toString());
        Layer l = d.executeSql("SELECT * FROM test_ogr_sql_execute_sql")) {
      assertEquals(10, l.count());
    }
    Layer l =
        Jdal.open(tmp.resolve("test_ogr_sql_execute_sql.shp").toString())
            .executeSql("SELECT * FROM test_ogr_sql_execute_sql");
    assertEquals(10, l.count());
  }

  @Port("autotest/ogr/ogr_sql_test.py::test_ogr_sql_unrecognized_dialect")
  @Test
  void unknownDialectFallsBackToOgrSql() {
    List<String> w =
        Diagnostics.capture(
            () -> {
              try (Layer l = ds.executeSql("SELECT * FROM poly WHERE eas_id < 167", "unknown")) {
                assertEquals(3, l.count());
              }
            });
    assertTrue(
        w.stream().anyMatch(m -> m.contains("Dialect 'unknown' is unsupported")), w.toString());
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_1",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_2",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_3",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_3_desc",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_4"
  })
  @Test
  void whereDistinctOrderBy() {
    Layer poly = ds.requireLayer("poly");
    assertEquals(3, countWhere(poly, "eas_id < 167"));
    assertEquals(10, poly.count());
    try (Layer l = ds.executeSql("SELECT * FROM poly WHERE eas_id < 167")) {
      assertEquals(3, l.count());
    }
    try (Layer l = ds.executeSql("select distinct eas_id from poly where eas_id < 170")) {
      check(l, "eas_id", 168, 169, 166, 158, 165);
    }
    try (Layer l =
        ds.executeSql("select distinct eas_id from poly where eas_id < 170 order by eas_id")) {
      check(l, "eas_id", 158, 165, 166, 168, 169);
    }
    try (Layer l =
        ds.executeSql("select distinct eas_id from poly where eas_id < 170 order by eas_id desc")) {
      check(l, "eas_id", 169, 168, 166, 165, 158);
    }
    try (Layer l = ds.executeSql("select distinct name from idlink order by name asc")) {
      check(l, "name", "_158_", "_165_", "_166_", "_168_", "_170_", "_171_", "_179_");
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_5",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_6"
  })
  @Test
  void columnFunctions() {
    try (Layer l =
        ds.executeSql(
            "select max(eas_id), min(eas_id), avg(eas_id), STDDEV_POP(eas_id), STDDEV_SAMP(eas_id),"
                + " sum(eas_id), count(eas_id) from idlink")) {
      Feature f = all(l).getFirst();
      assertEquals(179, ((Number) f.get("max_eas_id")).intValue());
      assertEquals(158, ((Number) f.get("min_eas_id")).intValue());
      assertEquals(168.142857142857, (Double) f.get("avg_eas_id"), 1e-12);
      assertEquals(5.9384599116647205, (Double) f.get("STDDEV_POP_eas_id"), 5.94 * 1e-15);
      assertEquals(6.414269805898183, (Double) f.get("STDDEV_SAMP_eas_id"), 6.42 * 1e-15);
      assertEquals(7, ((Number) f.get("count_eas_id")).intValue());
      assertEquals(1177, ((Number) f.get("sum_eas_id")).intValue());
    }
    try (Layer l = ds.executeSql("select count(*) from poly")) {
      check(l, "count_*", 10);
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_7",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_8",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_9",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_15",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_13"
  })
  @Test
  void fidWildcardQuotedTablesAndOgrGeometry() {
    try (Layer l = ds.executeSql("select eas_id, fid from poly where eas_id in (158,165)")) {
      check(l, "fid", 7, 8);
    }
    try (Layer l = ds.executeSql("select * from poly where eas_id in (158,165)")) {
      check(l, "PRFEDEA", "35043369", "35043408");
    }
    try (Layer l = ds.executeSql("select * from \"poly\" where eas_id in (158,165)")) {
      check(l, "PRFEDEA", "35043369", "35043408");
    }
    try (Layer l = ds.executeSql("select fid,eas_id,prfedea from poly where fid = 7")) {
      check(l, "fid", 7);
    }
    try (Layer l =
        ds.executeSql("select ogr_geometry from poly where ogr_geometry = 'MULTIPOLYGON'")) {
      check(
          l,
          "ogr_geometry",
          "MULTIPOLYGON",
          "MULTIPOLYGON",
          "MULTIPOLYGON",
          "MULTIPOLYGON",
          "MULTIPOLYGON",
          "MULTIPOLYGON",
          "MULTIPOLYGON",
          "MULTIPOLYGON",
          "MULTIPOLYGON",
          "MULTIPOLYGON");
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_ilike",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_like"
  })
  @Test
  void likeAndIlike() {
    try (Dataset csv = open("prime_meridian.csv")) {
      String base = "select * from prime_meridian where PRIME_MERIDIAN_NAME ";
      for (Object[] c :
          new Object[][] {
            {"ilike 'GREEN%'", 1},
            {"ilike '%WICH'", 1},
            {"ilike 'FOO%'", 0},
            {"like 'Green%'", 1},
            {"like '%wich'", 1},
            {"like 'GREEN%'", 0}
          }) {
        try (Layer l = csv.executeSql(base + c[0])) {
          assertEquals(((Number) c[1]).longValue(), l.count(), (String) c[0]);
        }
      }
      System.setProperty("OGR_SQL_LIKE_AS_ILIKE", "YES");
      try (Layer l = csv.executeSql(base + "like 'GREEN%'")) {
        assertEquals(1, l.count());
      } finally {
        System.clearProperty("OGR_SQL_LIKE_AS_ILIKE");
      }
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_11",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_12"
  })
  @Test
  void emptyLayers() {
    try (Dataset e = open("shp/empty.shp")) {
      try (Layer l = e.executeSql("select max(eas_id) from empty")) {
        check(l, "max_eas_id", new Object[] {null});
      }
      try (Layer l = e.executeSql("select distinct eas_id from empty")) {
        check(l, "eas_id");
      }
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_20",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_21",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_22",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_23"
  })
  @Test
  void wildcardsOnLayersWithFewFields() {
    Dataset m = mem();
    Layer noFields = layer(m, "my_layer");
    noFields.insert(Feature.builder(noFields.type()).geometry(Wkt.read("POINT(0 1)")).build());
    noFields.insert(Feature.builder(noFields.type()).geometry(Wkt.read("POINT(2 3)")).build());
    try (Layer l = m.executeSql("SELECT * from my_layer")) {
      assertEquals(2, l.count());
    }
    try (Layer l = m.executeSql("SELECT *, fid from my_layer")) {
      assertEquals(1, l.type().fields().size());
      assertEquals("fid", l.type().fields().getFirst().name());
    }
    Dataset m2 = mem();
    Layer lyr = layer(m2, "my_layer", FieldDefn.of("test", FieldType.STRING));
    lyr.insert(Feature.builder(lyr.type()).set(0, "0").geometry(Wkt.read("POINT(0 1)")).build());
    lyr.insert(Feature.builder(lyr.type()).set(0, "1").geometry(Wkt.read("POINT(2 3)")).build());
    try (Layer l = m2.executeSql("SELECT *, fid, *, my_layer.* from my_layer")) {
      assertEquals(
          List.of("test", "fid", "test", "my_layer.test"),
          l.type().fields().stream().map(FieldDefn::name).toList());
    }
    try (Layer l = m2.executeSql("SELECT DISTINCT test from my_layer")) {
      assertEquals(2, l.count());
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_25",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_26"
  })
  @Test
  void areaAndLiterals() {
    Dataset m = mem();
    Layer lyr = layer(m, "my_layer", FieldDefn.of("test", FieldType.STRING));
    lyr.insert(
        Feature.builder(lyr.type())
            .set(0, "0")
            .geometry(Wkt.read("POLYGON((0 0,0 1,1 1,1 0,0 0))"))
            .build());
    lyr.insert(
        Feature.builder(lyr.type())
            .set(0, "1")
            .geometry(Wkt.read("POLYGON((0 0,0 0.5,0.5 0.5,0.5 0,0 0))"))
            .build());
    try (Layer l =
        m.executeSql("SELECT test, OGR_GEOM_AREA from my_layer WHERE OGR_GEOM_AREA > 0.9")) {
      assertEquals(1, l.count());
      Feature f = all(l).getFirst();
      assertEquals(1.0, (Double) f.get("OGR_GEOM_AREA"));
      assertEquals("0", f.get("test"));
    }
    Dataset m2 = mem();
    Layer l2 = layer(m2, "my_layer");
    l2.insert(Feature.builder(l2.type()).build());
    try (Layer l =
        m2.executeSql(
            "SELECT 'literal_value' AS my_column, 'literal_value2' my_column2 FROM my_layer")) {
      Feature f = all(l).getFirst();
      assertEquals("literal_value", f.get("my_column"));
      assertEquals("literal_value2", f.get("my_column2"));
    }
  }

  @Port("autotest/ogr/ogr_sql_test.py::test_ogr_sql_27")
  @Test
  void queryOnDateTimeColumns() {
    try (Dataset csv = open("csv/testdatetime.csv")) {
      try (Layer l =
          csv.executeSql(
              "SELECT * FROM testdatetime WHERE timestamp < '2010/04/01 00:00:00' AND timestamp >"
                  + " '2009/11/15 11:59:59' AND timestamp != '2009/12/31 23:00:00' ORDER BY"
                  + " timestamp DESC")) {
        check(l, "name", "foo5", "foo4");
      }
    }
  }

  @Port("autotest/ogr/ogr_sql_test.py::test_ogr_sql_17")
  @Test
  void castSetsFieldDefinitions() {
    Dataset m = mem();
    Layer lyr = layerNoGeom(m, "small", FieldDefn.of("data", FieldType.REAL));
    lyr.insert(Feature.builder(lyr.type()).set(0, 1.2345).build());
    lyr.insert(Feature.builder(lyr.type()).set(0, 2.0).build());
    try (Layer l =
        m.executeSql("select CAST(fid as CHARACTER(10)), CAST(data as numeric(7,3)) from small")) {
      FieldDefn a = l.type().fields().get(0), b = l.type().fields().get(1);
      assertEquals("fid", a.name());
      assertEquals(FieldType.STRING, a.type());
      assertEquals(10, a.width());
      assertEquals("data", b.name());
      assertEquals(FieldType.REAL, b.type());
      assertEquals(7, b.width());
      assertEquals(3, b.precision());
      check(l, "fid", "0", "1");
    }
  }

  // ------------------------------------------------------------------ invalid statements

  static Stream<String> invalidStatements() {
    return Stream.of(
        "",
        "1",
        "*",
        "SELECT",
        "SELECT ' FROM my_layer",
        "SELECT + FROM my_layer",
        "SELECT (1 FROM my_layer",
        "SELECT (1)) FROM my_layer",
        "SELECT (1,) FROM my_layer",
        "SELECT 1 + FROM my_layer",
        "SELECT 1 + 'a' FROM my_layer",
        "SELECT 1 - FROM my_layer",
        "SELECT 1 * FROM my_layer",
        "SELECT 1 % FROM my_layer",
        "SELECT x.",
        "SELECT x AS",
        "SELECT *",
        "SELECT * FROM",
        "SELECT * FROM foo",
        "SELECT FROM my_layer",
        "SELECT FROM FROM my_layer",
        "SELECT ('strfield'",
        "SELECT 'strfield' +",
        "SELECT 'strfield' 'strfield'",
        "SELECT CONCAT('strfield')",
        "SELECT foo(strfield) FROM my_layer",
        "SELECT strfield, FROM my_layer",
        "SELECT strfield, foo FROM my_layer",
        "SELECT strfield AS FROM my_layer",
        "SELECT strfield AS 1 FROM my_layer",
        "SELECT strfield AS strfield2 FROM",
        "SELECT strfield + intfield FROM my_layer",
        "SELECT CAST",
        "SELECT CAST(",
        "SELECT CAST(strfield",
        "SELECT CAST(strfield AS",
        "SELECT CAST(strfield AS foo",
        "SELECT CAST(strfield AS foo)",
        "SELECT CAST(strfield AS foo) FROM",
        "SELECT CAST(strfield AS foo) FROM my_layer",
        "SELECT CAST(strfield AS CHARACTER",
        "SELECT CAST(strfield AS CHARACTER)",
        "SELECT CAST(strfield AS CHARACTER) FROM",
        "SELECT CAST(strfield AS CHARACTER) FROM foo",
        "SELECT CAST(strfield AS CHARACTER(",
        "SELECT CAST(strfield AS CHARACTER(2",
        "SELECT CAST(strfield AS CHARACTER(2)",
        "SELECT CAST(strfield AS CHARACTER(2))",
        "SELECT CAST(strfield AS CHARACTER(2)) FROM",
        "SELECT CAST(strfield AS CHARACTER(2)) FROM foo",
        "SELECT CAST(strfield AS 1) FROM my_layer",
        "SELECT * FROM my_layer WHERE",
        "SELECT * FROM my_layer WHERE strfield = ",
        "SELECT * FROM my_layer WHERE strfield = foo",
        "SELECT * FROM my_layer WHERE foo = 'a'",
        "SELECT * FROM my_layer WHERE strfield = 'aSELECT * FROM my_layer WHERE strfield = 'a'"
            + " ORDER ",
        "SELECT * FROM my_layer WHERE strfield = 'a' ORDER BY",
        "SELECT * FROM my_layer WHERE strfield = 'a' ORDER BY foo",
        "SELECT * FROM my_layer WHERE strfield = 'a' ORDER BY strfield UNK",
        "SELECT * FROM my_layer ORDER BY geom",
        "SELECT FOO(*) FROM my_layer",
        "SELECT FOO(*) AS bar FROM my_layer",
        "SELECT COUNT",
        "SELECT COUNT(",
        "SELECT COUNT() FROM my_layer",
        "SELECT COUNT(*",
        "SELECT COUNT(*)",
        "SELECT COUNT(*) FROM",
        "SELECT COUNT(*) AS foo FROM",
        "SELECT COUNT(* FROM my_layer",
        "SELECT COUNT(i_dont_exist) FROM my_layer",
        "SELECT COUNT(FOO intfield) FROM my_layer",
        "SELECT COUNT(DISTINCT intfield FROM my_layer",
        "SELECT COUNT(DISTINCT i_dont_exist) FROM my_layer",
        "SELECT COUNT(DISTINCT *) FROM my_layer",
        "SELECT FOO(DISTINCT intfield) FROM my_layer",
        "SELECT FOO(DISTINCT intfield) as foo FROM my_layer",
        "SELECT DISTINCT foo FROM my_layer",
        "SELECT DISTINCT foo AS 'id' 'id2' FROM",
        "SELECT DISTINCT foo AS id id2 FROM",
        "SELECT DISTINCT FROM my_layer",
        "SELECT DISTINCT strfield, COUNT(DISTINCT intfield) FROM my_layer",
        "SELECT MIN(intfield*2) FROM my_layer",
        "SELECT MIN(intfield,2) FROM my_layer",
        "SELECT MIN(foo) FROM my_layer",
        "SELECT MAX(foo) FROM my_layer",
        "SELECT SUM(foo) FROM my_layer",
        "SELECT AVG(foo) FROM my_layer",
        "SELECT STDDEV_POP(foo) FROM my_layer",
        "SELECT STDDEV_SAMP(foo) FROM my_layer",
        "SELECT SUM(strfield) FROM my_layer",
        "SELECT AVG(strfield) FROM my_layer",
        "SELECT AVG(intfield, intfield) FROM my_layer",
        "SELECT STDDEV_POP(strfield) FROM my_layer",
        "SELECT STDDEV_SAMP(strfield) FROM my_layer",
        "SELECT * FROM my_layer WHERE AVG(intfield) = 1",
        "SELECT * FROM my_layer WHERE STDDEV_POP(intfield) = 1",
        "SELECT * FROM 'foo' foo",
        "SELECT * FROM my_layer WHERE strfield =",
        "SELECT * FROM my_layer WHERE strfield = foo",
        "SELECT * FROM my_layer WHERE strfield = intfield",
        "SELECT * FROM my_layer WHERE strfield = 1",
        "SELECT * FROM my_layer WHERE strfield = '1' AND",
        "SELECT * FROM my_layer WHERE strfield LIKE",
        "SELECT * FROM my_layer WHERE strfield LIKE 1",
        "SELECT * FROM my_layer WHERE strfield IS",
        "SELECT * FROM my_layer WHERE strfield IS NOT",
        "SELECT * FROM my_layer WHERE strfield IS foo",
        "SELECT * FROM my_layer WHERE strfield IS NOT foo",
        "SELECT * FROM my_layer WHERE (strfield IS NOT NULL",
        "SELECT * FROM my_layer WHERE strfield IN",
        "SELECT * FROM my_layer WHERE strfield IN(",
        "SELECT * FROM my_layer WHERE strfield IN()",
        "SELECT * FROM my_layer WHERE strfield IN('a'",
        "SELECT * FROM my_layer WHERE strfield IN('a',",
        "SELECT * FROM my_layer WHERE strfield IN('a','b'",
        "SELECT * FROM my_layer WHERE strfield IN('a','b'))",
        "SELECT * FROM my_layer LEFT",
        "SELECT * FROM my_layer LEFT JOIN",
        "SELECT * FROM my_layer LEFT JOIN foo",
        "SELECT * FROM my_layer LEFT JOIN foo ON my_layer.strfield = my_layer2.strfield",
        "SELECT * FROM my_layer LEFT JOIN my_layer2 ON my_layer.strfield = foo.strfield",
        "SELECT * FROM my_layer LEFT JOIN my_layer2 ON my_layer.strfield = my_layer2.foo",
        "SELECT *, my_layer2. FROM my_layer LEFT JOIN my_layer2 ON my_layer.strfield ="
            + " my_layer2.strfield",
        "SELECT *, my_layer2.foo FROM my_layer LEFT JOIN my_layer2 ON my_layer.strfield ="
            + " my_layer2.strfield",
        "SELECT * FROM my_layer UNION",
        "SELECT * FROM my_layer UNION ALL",
        "SELECT * FROM my_layer UNION ALL SELECT",
        "SELECT * FROM my_layer UNION ALL SELECT *",
        "SELECT * FROM my_layer UNION ALL SELECT * FROM");
  }

  @Port("autotest/ogr/ogr_sql_test.py::test_ogr_sql_invalid_statements")
  @ParameterizedTest
  @MethodSource("invalidStatements")
  void invalidStatementsAreRejected(String sql) {
    Dataset m = mem();
    layer(
        m,
        "my_layer",
        FieldDefn.of("strfield", FieldType.STRING),
        FieldDefn.of("intfield", FieldType.INTEGER));
    layerNoGeom(
        m,
        "my_layer2",
        FieldDefn.of("strfield", FieldType.STRING),
        FieldDefn.of("strfield2", FieldType.STRING));
    // the geometry of my_layer is called "geom" in the original fixture
    assertThrows(JdalException.class, () -> m.executeSql(sql), sql);
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_29",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_30",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_31",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_32"
  })
  @Test
  void isNullAndSummaryMixing() {
    Dataset m = mem();
    Layer lyr = layerNoGeom(m, "my_layer", FieldDefn.of("strfield", FieldType.STRING));
    lyr.insert(Feature.builder(lyr.type()).set(0, "a").build());
    lyr.insert(Feature.builder(lyr.type()).set(0, "b").build());
    lyr.insert(Feature.builder(lyr.type()).build());
    try (Layer l = m.executeSql("select * from my_layer where strfield is null")) {
      assertEquals(1, l.count());
    }
    try (Layer l = m.executeSql("select * from my_layer where strfield is not null")) {
      assertEquals(2, l.count());
    }
    try (Layer l = ds.executeSql("select min(eas_id), count(*) from poly")) {
      assertEquals(10, all(l).getFirst().get(1));
    }
    for (String dialect : new String[] {null, "OGRSQL"}) {
      try (Layer l = ds.executeSql("select min(eas_id) from poly where area = 0", dialect)) {
        assertNull(all(l).getFirst().get(0));
      }
    }
  }

  @Port("autotest/ogr/ogr_sql_test.py::test_ogr_sql_33")
  @Test
  void alterTable() {
    Dataset m = mem();
    Layer lyr = layerNoGeom(m, "my_layer");
    for (String kw : new String[] {"COLUMN ", ""}) {
      assertNull(m.executeSql("ALTER TABLE my_layer ADD " + kw + "myfield NUMERIC(20, 8)"));
      FieldDefn f = lyr.type().fields().get(lyr.type().requireFieldIndex("myfield"));
      assertEquals(FieldType.REAL, f.type());
      assertEquals(20, f.width());
      assertEquals(8, f.precision());
      assertNull(m.executeSql("ALTER TABLE my_layer RENAME " + kw + "myfield TO \"myfield 2\""));
      assertTrue(lyr.type().fieldIndex("myfield").isEmpty());
      assertTrue(lyr.type().fieldIndex("myfield 2").isPresent());
      assertNull(m.executeSql("ALTER TABLE my_layer ALTER " + kw + "\"myfield 2\" TYPE CHARACTER"));
      assertEquals(
          FieldType.STRING,
          lyr.type().fields().get(lyr.type().requireFieldIndex("myfield 2")).type());
      assertNull(
          m.executeSql("ALTER TABLE my_layer ALTER " + kw + "\"myfield 2\" TYPE CHARACTER(15)"));
      assertEquals(15, lyr.type().fields().get(lyr.type().requireFieldIndex("myfield 2")).width());
      assertNull(m.executeSql("ALTER TABLE my_layer DROP " + kw + "\"myfield 2\""));
      assertTrue(lyr.type().fieldIndex("myfield 2").isEmpty());
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_34",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_35"
  })
  @Test
  void stringToNumericAndHugeQueries() {
    try (Layer l = ds.executeSql("select count(*) from poly where eas_id in ('165')")) {
      assertEquals(1, all(l).getFirst().get(0));
    }
    List<String> w =
        Diagnostics.capture(
            () ->
                assertThrows(
                    JdalException.class,
                    () -> ds.executeSql("select count(*) from poly where eas_id in ('a165')")));
    assertTrue(
        w.stream()
            .anyMatch(
                m -> m.contains("Conversion failed when converting the string value 'a165'")));
    String cols = "area";
    for (int i = 0; i < 10; i++) cols = cols + "," + cols;
    try (Layer l = ds.executeSql("select " + cols + " from poly")) {
      assertEquals(1024, l.type().fields().size());
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_36",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_count_and_null"
  })
  @Test
  void nullsInDistinctAndCounts() {
    Dataset m = mem();
    Layer lyr =
        layerNoGeom(
            m,
            "layer",
            FieldDefn.of("intfield", FieldType.INTEGER),
            FieldDefn.of("floatfield", FieldType.REAL),
            FieldDefn.of("strfield", FieldType.STRING),
            FieldDefn.of("int64field", FieldType.INTEGER64));
    lyr.insert(
        Feature.builder(lyr.type())
            .set(0, 1)
            .set(1, 2.3)
            .set(2, "456")
            .set(3, 1234567890123L)
            .build());
    lyr.insert(Feature.builder(lyr.type()).build());
    for (String f : List.of("intfield", "int64field", "floatfield", "strfield")) {
      try (Layer l = m.executeSql("select distinct " + f + " from layer order by " + f + " asc")) {
        List<Feature> fs = all(l);
        assertNull(fs.get(0).get(0), f);
        assertNotNull(fs.get(1).get(0), f);
      }
      try (Layer l = m.executeSql("select distinct " + f + " from layer order by " + f + " desc")) {
        List<Feature> fs = all(l);
        assertNotNull(fs.get(0).get(0), f);
        assertNull(fs.get(1).get(0), f);
      }
    }
    Dataset m2 = mem();
    Layer l2 =
        layerNoGeom(
            m2,
            "layer",
            FieldDefn.of("intfield", FieldType.INTEGER),
            FieldDefn.of("floatfield", FieldType.REAL),
            FieldDefn.of("strfield", FieldType.STRING),
            FieldDefn.of("strfield_first_null", FieldType.STRING),
            FieldDefn.of("strfield_never_set", FieldType.STRING),
            FieldDefn.of("intfield_never_set", FieldType.INTEGER));
    l2.insert(Feature.builder(l2.type()).set(0, 1).set(2, "456").build());
    l2.insert(Feature.builder(l2.type()).set(0, 1).set(2, "456").build());
    l2.insert(Feature.builder(l2.type()).set(1, 2.3).set("strfield_first_null", "foo").build());
    l2.insert(Feature.builder(l2.type()).set(1, 2.3).build());
    for (String f : List.of("intfield", "floatfield", "strfield")) {
      try (Layer l =
          m2.executeSql(
              "select count(" + f + "), count(distinct " + f + "), count(*) from layer")) {
        Feature r = all(l).getFirst();
        assertEquals(2, ((Number) r.get(0)).intValue(), f);
        assertEquals(1, ((Number) r.get(1)).intValue(), f);
        assertEquals(4, ((Number) r.get(2)).intValue(), f);
      }
    }
    try (Layer l =
        m2.executeSql(
            "select avg(intfield), STDDEV_POP(intfield) from layer where intfield is null")) {
      Feature r = all(l).getFirst();
      assertNull(r.get(0));
      assertNull(r.get(1));
    }
    try (Layer l = m2.executeSql("select distinct strfield_first_null from layer")) {
      List<Feature> fs = all(l);
      assertNull(fs.get(0).get(0));
      assertEquals("foo", fs.get(1).get(0));
    }
    try (Layer l = m2.executeSql("select distinct strfield_never_set from layer")) {
      assertNull(all(l).getFirst().get(0));
    }
    try (Layer l =
        m2.executeSql(
            "select min(intfield_never_set), max(intfield_never_set), avg(intfield_never_set),"
                + " sum(intfield_never_set), count(intfield_never_set) from layer")) {
      Feature r = all(l).getFirst();
      for (int i = 0; i < 4; i++) assertNull(r.get(i));
      assertEquals(0, ((Number) r.get(4)).intValue());
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_38",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_39",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_40",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_41",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_42",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_43"
  })
  @Test
  void specialFieldsOrderingAndEscapes() {
    try (Layer l = ds.executeSql("SELECT MAX(OGR_GEOM_AREA) FROM poly")) {
      assertEquals(1634833.39062, (Double) all(l).getFirst().get(0), 1e-5);
    }
    try (Layer l = ds.executeSql("SELECT * FROM poly ORDER BY OGR_GEOM_AREA")) {
      assertEquals(5268.813, (Double) all(l).getFirst().get(0), 1e-5);
    }
    try (Layer l = ds.executeSql("SELECT * FROM poly ORDER BY FID DESC")) {
      assertEquals(9, all(l).getFirst().fid());
    }
    try (Layer l = ds.executeSql("SELECT * FROM poly ORDER BY OGR_GEOMETRY")) {
      assertEquals(0, all(l).getFirst().fid());
    }
    assertTrue(countWhere(ds.requireLayer("poly"), "prfedea <> ''") > 0);
    try (Layer l = ds.executeSql("SELECT * FROM poly WHERE prfedea <> ''")) {
      assertTrue(l.count() > 0);
    }
    try (Layer l = ds.executeSql("SELECT '\"' as a, '\\' as b, '''' as c FROM poly")) {
      Feature f = all(l).getFirst();
      assertEquals("\"", f.get("a"));
      assertEquals("\\", f.get("b"));
      assertEquals("'", f.get("c"));
    }
  }

  @Port({"autotest/ogr/ogr_sql_test.py::test_ogr_sql_hstore_get_value_invalid_parameters"})
  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT hstore_get_value('a') FROM poly",
        "SELECT hstore_get_value(1, 1) FROM poly"
      })
  void hstoreInvalidParameters(String sql) {
    assertThrows(JdalException.class, () -> ds.executeSql(sql));
  }

  @Port(
      "autotest/ogr/ogr_sql_test.py::test_ogr_sql_hstore_get_value_invalid_hstore_syntax_or_empty_result")
  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT hstore_get_value('a', null) FROM poly",
        "SELECT hstore_get_value(null, 'a') FROM poly",
        "SELECT hstore_get_value('a', 'a') FROM poly",
        "SELECT hstore_get_value('a=>b', 'c') FROM poly",
        "SELECT hstore_get_value('a=>', 'a') FROM poly",
        "SELECT hstore_get_value(' a => ', 'a') FROM poly",
        "SELECT hstore_get_value('a=>b,z,c=>d', 'c') FROM poly",
        "SELECT hstore_get_value('\"a', 'a') FROM poly",
        "SELECT hstore_get_value('\"a\"', 'a') FROM poly",
        "SELECT hstore_get_value('\"a\"=', 'a') FROM poly",
        "SELECT hstore_get_value('\"a\" =>', 'a') FROM poly",
        "SELECT hstore_get_value('\"a\" => ', 'a') FROM poly",
        "SELECT hstore_get_value('\"a\" => \"', 'a') FROM poly",
        "SELECT hstore_get_value('\"a\" => \"\" z', 'a') FROM poly"
      })
  void hstoreInvalidSyntaxOrEmpty(String sql) {
    try (Layer l = ds.executeSql(sql)) {
      assertNull(all(l).getFirst().get(0), sql);
    }
  }

  @Port("autotest/ogr/ogr_sql_test.py::test_ogr_sql_hstore_get_value_valid")
  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT hstore_get_value('a=>b', 'a') FROM poly",
        "SELECT hstore_get_value(' a => b ', 'a') FROM poly",
        "SELECT hstore_get_value('\"a\"=>b', 'a') FROM poly",
        "SELECT hstore_get_value(' \"a\" =>b', 'a') FROM poly",
        "SELECT hstore_get_value('a=>\"b\"', 'a') FROM poly",
        "SELECT hstore_get_value('a=> \"b\" ', 'a') FROM poly",
        "SELECT hstore_get_value('\"a\"=>\"b\"', 'a') FROM poly",
        "SELECT hstore_get_value(' \"a\" => \"b\" ', 'a') FROM poly",
        "SELECT hstore_get_value(' \"a\\\"b\" => \"b\" ', 'a\"b') FROM poly"
      })
  void hstoreValid(String sql) {
    try (Layer l = ds.executeSql(sql)) {
      assertEquals("b", all(l).getFirst().get(0), sql);
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_strict_quoting_non_aggregate",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_strict_quoting_aggregate",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_strict_quoting_errors"
  })
  @Test
  void strictQuoting() {
    Dataset m = mem();
    Layer lyr =
        layerNoGeom(
            m,
            "test",
            FieldDefn.of("id", FieldType.INTEGER),
            FieldDefn.of("from", FieldType.STRING));
    lyr.insert(Feature.builder(lyr.type()).set(0, 1).set(1, "not_from").build());
    lyr.insert(Feature.builder(lyr.type()).set(0, 3).set(1, "from").build());
    try (Layer l =
        m.executeSql(
            "select id, 'id', \"id\" as id2, id as \"id3\", \"from\" from test where \"from\" ="
                + " 'from'")) {
      List<Feature> fs = all(l);
      assertEquals(1, fs.size());
      assertEquals(List.of(3, "id", 3, 3, "from"), fs.getFirst().values());
    }
    try (Layer l =
        m.executeSql("select max(\"id\"), max(id), count(\"id\"), count(id) from \"test\"")) {
      List<Object> v = all(l).getFirst().values();
      assertEquals(3, ((Number) v.get(0)).intValue());
      assertEquals(3, ((Number) v.get(1)).intValue());
      assertEquals(2, ((Number) v.get(2)).intValue());
      assertEquals(2, ((Number) v.get(3)).intValue());
    }
    for (String sql :
        List.of(
            "select * from 'test'",
            "select distinct 'id' from 'test'",
            "select max('id') from 'test'",
            "select id as 'id2' from 'test'")) {
      assertThrows(JdalException.class, () -> m.executeSql(sql), sql);
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_47",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_48"
  })
  @Test
  void sortingNullsAndManyRows() {
    try (Dataset d = open("shp/sort_test.dbf");
        Layer l = d.executeSql("SELECT * FROM sort_test ORDER BY text_value")) {
      String prev = "";
      for (Feature f : all(l)) {
        String v = f.get("text_value") == null ? "" : (String) f.get("text_value");
        assertTrue(v.compareTo(prev) >= 0, "new_val = '" + v + "', prec_val = '" + prev + "'");
        prev = v;
      }
    }
    Dataset m = mem();
    Layer lyr = layerNoGeom(m, "test", FieldDefn.of("int_field", FieldType.INTEGER));
    for (int i = 0; i < 1000; i++)
      lyr.insert(Feature.builder(lyr.type()).set(0, i % 2 == 0 ? i + 1 : 1001 - i).build());
    try (Layer l = m.executeSql("SELECT * FROM test ORDER BY int_field")) {
      int i = 1;
      for (Feature f : all(l)) assertEquals(i++, f.get("int_field"));
      assertEquals(1001, i);
      for (int k = 0; k < 1000; k++)
        assertEquals(
            lyr.feature(k).orElseThrow().get(0), l.feature(k).orElseThrow().get("int_field"));
    }
  }

  @Port("autotest/ogr/ogr_sql_test.py::test_ogr_sql_49")
  @Test
  void arithmeticExpressions() {
    Object[][] expressions = {
      {"1/1", 1},
      {"1/1.", 1.0},
      {"cast((1) as integer)/1.", 1.0},
      {"1./cast((1) as integer)", 1.0},
      {"1.5+1", 2.5},
      {"(1*1)+1.5", 2.5},
      {"1+1", 2},
      {"cast(1 as integer)+ 1234567890123", 1234567890124L},
      {"cast(1 as integer)* 1234567890123", 1234567890123L}
    };
    for (Object[] e : expressions) {
      try (Layer l = ds.executeSql("select " + e[0] + " as result from poly limit 1")) {
        check(l, "result", e[1]);
      }
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_field_names_same_case",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_string_int_array_comparison",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_min_max_string_field"
  })
  @Test
  void caseListsAndStringAggregates() {
    Dataset m = mem();
    Layer same =
        layerNoGeom(
            m,
            "test",
            FieldDefn.of("id", FieldType.STRING),
            FieldDefn.of("ID", FieldType.STRING),
            FieldDefn.of("ID2", FieldType.STRING));
    same.insert(Feature.builder(same.type()).set(0, "foo").set(1, "bar").set(2, "baz").build());
    try (Layer l = m.executeSql("SELECT * FROM test")) {
      Feature f = all(l).getFirst();
      assertEquals(List.of("foo", "bar", "baz"), f.values());
    }
    Layer arr =
        layerNoGeom(
            m,
            "arrays",
            FieldDefn.of("id", FieldType.STRING),
            FieldDefn.of("int_array", FieldType.INTEGER_LIST));
    arr.insert(Feature.builder(arr.type()).set(0, "foo").set(1, List.of(1, 2)).build());
    assertEquals(1, countWhere(arr, "id = 'foo'"));
    for (String op : List.of("=", "<>", "<", "<=", ">", ">="))
      assertEquals(0, countWhere(arr, "int_array " + op + " 1"), op);
    assertEquals(0, countWhere(arr, "int_array BETWEEN 0 AND 3"));
    assertEquals(0, countWhere(arr, "int_array IS NULL"));
    assertEquals(0, countWhere(arr, "int_array IN (1, 2)"));
    Layer s = layerNoGeom(m, "strings", FieldDefn.of("str_field", FieldType.STRING));
    try (Layer l = m.executeSql("select min(str_field), max(str_field) from strings")) {
      Feature f = all(l).getFirst();
      assertNull(f.get("MIN_str_field"));
      assertNull(f.get("MAX_str_field"));
    }
    for (String v : List.of("z", "b", "ab")) s.insert(Feature.builder(s.type()).set(0, v).build());
    try (Layer l = m.executeSql("select min(str_field), max(str_field) from strings")) {
      Feature f = all(l).getFirst();
      assertEquals("ab", f.get("MIN_str_field"));
      assertEquals("z", f.get("MAX_str_field"));
    }
  }

  @Port(
      "autotest/ogr/ogr_sql_test.py::test_ogr_sql_attribute_filter_on_top_of_non_forward_where_clause")
  @ParameterizedTest
  @ValueSource(strings = {"", "OGRSQL"})
  void attributeFilterOnTopOfSelect(String dialect) {
    Dataset m = mem();
    Layer lyr = layer(m, "test");
    lyr.insert(Feature.builder(lyr.type()).geometry(Wkt.read("POLYGON EMPTY")).build());
    lyr.insert(Feature.builder(lyr.type()).geometry(Wkt.read("MULTIPOLYGON EMPTY")).build());
    try (Layer l =
        m.executeSql(
            "SELECT * FROM test WHERE OGR_GEOMETRY = 'POLYGON'",
            dialect.isEmpty() ? null : dialect)) {
      assertEquals(1, l.count());
      assertEquals(1, l.count(Query.sql("1")));
      assertEquals(0, l.count(Query.sql("0")));
    }
  }

  @Port({"autotest/ogr/ogr_sql_test.py::test_ogr_sql_select_except_errors"})
  @ParameterizedTest
  @ValueSource(
      strings = {
        "(",
        ")",
        "()",
        "(*)",
        "(EASID, ",
        "(EASID, DOES_NOT_EXIST)",
        "(EAS_ID, EAS_ID)",
        "(EAS_ID,, AREA)"
      })
  void selectExceptErrors(String body) {
    assertThrows(
        JdalException.class, () -> ds.executeSql("SELECT * EXCEPT " + body + " FROM poly"));
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_select_except_attrs",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_select_except_geom",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_select_except_multiple_asterisk_1",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_select_except_multiple_asterisk_2"
  })
  @Test
  void selectExcept() {
    try (Layer l = ds.executeSql("SELECT * EXCEPT (EAS_ID, PRFEDEA) FROM poly")) {
      assertEquals(1, l.type().fields().size());
      assertEquals(1, l.type().geomFields().size());
    }
    try (Layer l = ds.executeSql("SELECT * EXCEPT (EAS_ID, \"_ogr_geometry_\") FROM poly")) {
      assertEquals(2, l.type().fields().size());
      assertEquals(0, l.type().geomFields().size());
    }
    try (Layer l = ds.executeSql("SELECT * EXCEPT (EAS_ID), * FROM poly")) {
      assertEquals(
          List.of("AREA", "PRFEDEA", "AREA", "EAS_ID", "PRFEDEA"),
          l.type().fields().stream().map(FieldDefn::name).toList());
      assertEquals(2, l.type().geomFields().size());
    }
    try (Layer l = ds.executeSql("SELECT *, * EXCEPT (EAS_ID) FROM poly")) {
      assertEquals(
          List.of("AREA", "EAS_ID", "PRFEDEA", "AREA", "PRFEDEA"),
          l.type().fields().stream().map(FieldDefn::name).toList());
      assertEquals(2, l.type().geomFields().size());
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_select_except_named_geometry",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_select_except_join_1",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_select_except_join_2",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_select_except_join_3"
  })
  @Test
  void exceptWithNamedGeometryAndJoins() {
    Dataset m = mem();
    Layer lyr =
        m.createLayer(
            new FeatureType(
                "my_layer",
                List.of(
                    FieldDefn.of("id", FieldType.INTEGER), FieldDefn.of("name", FieldType.STRING)),
                List.of(
                    new GeomFieldDefn(
                        "named_geom", GeometryType.UNKNOWN, Dimension.XY, null, true))),
            Map.of());
    lyr.insert(
        Feature.builder(lyr.type())
            .set("id", 3)
            .set("name", "test")
            .geometry(Wkt.read("POINT (0 0)"))
            .build());
    try (Layer l = m.executeSql("SELECT * EXCEPT (named_geom, id) FROM my_layer")) {
      assertEquals(0, l.type().geomFields().size());
      assertEquals(List.of("name"), l.type().fields().stream().map(FieldDefn::name).toList());
    }
    Dataset j = mem();
    Layer pt =
        layer(
            j,
            "point",
            FieldDefn.of("id", FieldType.INTEGER),
            FieldDefn.of("name", FieldType.STRING));
    pt.insert(
        Feature.builder(pt.type())
            .set("id", 1)
            .set("name", "test")
            .geometry(Wkt.read("POINT (0 0)"))
            .build());
    Layer line = layer(j, "line", FieldDefn.of("id", FieldType.INTEGER));
    line.insert(
        Feature.builder(line.type())
            .set("id", 1)
            .geometry(Wkt.read("LINESTRING (1 1, 2 2)"))
            .build());
    try (Layer l = j.executeSql("SELECT * FROM point JOIN line ON point.id = line.id")) {
      assertEquals(1, l.type().geomFields().size());
      assertEquals(GeometryType.POINT, all(l).getFirst().geometry().type());
      assertEquals(
          List.of("id", "name", "line.id"),
          l.type().fields().stream().map(FieldDefn::name).toList());
      assertEquals(1, all(l).getFirst().get("line.id"));
    }
    try (Layer l =
        j.executeSql("SELECT * EXCEPT (id) FROM point JOIN line ON point.id = line.id")) {
      assertEquals(
          List.of("name", "line.id"), l.type().fields().stream().map(FieldDefn::name).toList());
    }
    try (Layer l =
        j.executeSql("SELECT * EXCLUDE (line.id) FROM point JOIN line ON point.id = line.id")) {
      assertEquals(List.of("id", "name"), l.type().fields().stream().map(FieldDefn::name).toList());
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_like_utf8",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_ilike_utf8"
  })
  @Test
  void likeWithUtf8() {
    Layer lyr = layerNoGeom(mem(), "test");
    lyr.insert(Feature.builder(lyr.type()).build());
    Object[][] like = {
      {"'é' LIKE 'É'", 0},
      {"'É' LIKE 'é'", 0},
      {"'É' LIKE 'É'", 1},
      {"'é' LIKE 'e'", 0},
      {"'é' LIKE 'ê'", 0},
      {"'é' LIKE ''", 0},
      {"'é' LIKE '_'", 1},
      {"'éven' LIKE '_ven'", 1},
      {"'éven' LIKE '%ven'", 1},
      {"'' LIKE '_'", 0},
      {"'éven' LIKE '_xen'", 0},
      {"'éven' LIKE '%xen'", 0},
      {"'é' ILIKE 'é'", 1},
      {"'é' ILIKE 'É'", 1},
      {"'É' ILIKE 'é'", 1},
      {"'É' ILIKE 'É'", 1},
      {"'é' ILIKE 'e'", 0},
      {"'é' ILIKE 'ê'", 0},
      {"'é' ILIKE ''", 0},
      {"'é' ILIKE '_'", 1},
      {"'éven' ILIKE '_ven'", 1},
      {"'éven' ILIKE '%ven'", 1},
      {"'' ILIKE '_'", 0},
      {"'éven' ILIKE '_xen'", 0},
      {"'éven' ILIKE '%xen'", 0}
    };
    for (Object[] c : like)
      assertEquals(((Number) c[1]).longValue(), countWhere(lyr, (String) c[0]), (String) c[0]);
  }

  @Port(
      "autotest/ogr/ogr_sql_test.py::test_ogr_sql_test_execute_sql_error_on_spatial_filter_mem_layer")
  @Test
  void spatialFilterNeedsAGeometryField() {
    Dataset m = mem();
    layerNoGeom(m, "test");
    JdalException e =
        assertThrows(
            JdalException.class,
            () ->
                m.executeSql(
                    "SELECT 1 FROM test", Wkt.read("POLYGON((0 0,0 1,1 1,1 0,0 0))"), null));
    assertTrue(
        e.getMessage().contains("Cannot set spatial filter: no geometry field present in layer"));
  }

  @Port("autotest/ogr/ogr_sql_test.py::test_ogr_sql_test_spatial_filter_on_summary_result_set")
  @Test
  void spatialFilterOnSummary() {
    Dataset m = mem();
    Layer lyr = layer(m, "test", FieldDefn.of("x", FieldType.REAL));
    lyr.insert(Feature.builder(lyr.type()).set(0, 1.0).geometry(Wkt.read("POINT (1 1)")).build());
    lyr.insert(Feature.builder(lyr.type()).set(0, 2.0).geometry(Wkt.read("POINT (2 2)")).build());
    try (Layer l =
        m.executeSql(
            "SELECT SUM(x) FROM test",
            Wkt.read("POLYGON((1.5 1.5,1.5 2.5,2.5 2.5,2.5 1.5,1.5 1.5))"),
            null)) {
      assertEquals(2.0, ((Number) all(l).getFirst().get(0)).doubleValue());
    }
  }

  // ------------------------------------------------------------------ NULL semantics

  static Stream<Object[]> nullCases() {
    Object[][] c = {
      {"1 + intfield >= 0", 1},
      {"intfield = 0", 0},
      {"intfield = 1", 1},
      {"NOT intfield = 0", 1},
      {"NOT intfield = 1", 0},
      {"intfield IS NULL", 1},
      {"intfield IS NOT NULL", 1},
      {"intfield IN (NULL)", 0},
      {"NULL IN (NULL)", 0},
      {"NULL NOT IN (NULL)", 0},
      {"intfield NOT IN (NULL)", 0},
      {"intfield IN (1, NULL)", 1},
      {"intfield IN (0, NULL)", 0},
      {"intfield IN (NULL, 1)", 1},
      {"intfield IN (NULL, 0)", 0},
      {"intfield NOT IN (1, NULL)", 0},
      {"intfield NOT IN (0, NULL)", 0},
      {"intfield NOT IN (NULL, 1)", 0},
      {"intfield NOT IN (NULL, 0)", 0},
      {"(NOT intfield = 0) OR intfield IS NULL", 2},
      {"NOT (intfield = 0 OR intfield = 0)", 1},
      {"(NOT intfield = 0) AND NOT (intfield = 0)", 1},
      {"NOT (intfield = 0 OR intfield IS NULL)", 1},
      {"(NOT intfield = 0) AND NOT (intfield IS NULL)", 1},
      {"NOT (intfield = 0 OR intfield IS NOT NULL)", 0},
      {"(NOT intfield = 0) AND NOT (intfield IS NOT NULL)", 0},
      {"intfield <> 0 AND intfield <> 2", 1},
      {"intfield IS NOT NULL AND intfield NOT IN (2)", 1},
      {"NOT(intfield NOT IN (1) AND NULL NOT IN (1))", 1},
      {"NOT(intfield IS NOT NULL AND intfield NOT IN (2))", 1},
      {"NOT(NOT(intfield IS NOT NULL AND intfield NOT IN (2)))", 1},
      {"NOT (intfield = 0 AND intfield = 0)", 1},
      {"(intfield NOT IN (1) AND NULL NOT IN (1)) IS NULL", 1},
      {"1 + realfield >= 0", 1},
      {"realfield = 0", 0},
      {"realfield = 1", 1},
      {"NOT realfield = 0", 1},
      {"NOT realfield = 1", 0},
      {"realfield IS NULL", 1},
      {"realfield IS NOT NULL", 1},
      {"realfield IN (NULL)", 0},
      {"realfield NOT IN (NULL)", 0},
      {"realfield IN (1, NULL)", 1},
      {"realfield IN (0, NULL)", 0},
      {"realfield NOT IN (1, NULL)", 0},
      {"realfield NOT IN (0, NULL)", 0},
      {"(NOT realfield = 0) OR realfield IS NULL", 2},
      {"NOT (realfield = 0 OR realfield = 0)", 1},
      {"NOT (realfield = 0 OR realfield IS NULL)", 1},
      {"NOT (realfield = 0 OR realfield IS NOT NULL)", 0},
      {"strfield = ''", 0},
      {"strfield = 'foo'", 1},
      {"NOT strfield = ''", 1},
      {"NOT strfield = 'foo'", 0},
      {"strfield IS NULL", 1},
      {"strfield IS NOT NULL", 1},
      {"strfield IN ('foo', NULL)", 1},
      {"strfield NOT IN ('foo', NULL)", 0},
      {"strfield IN ('', NULL)", 0},
      {"strfield NOT IN ('', NULL)", 0},
      {"datetimefield = '1970-01-01T00:00:00'", 0},
      {"datetimefield = '2024-01-01T00:00:00'", 1},
      {"NOT datetimefield = '1970-01-01T00:00:00'", 1},
      {"NOT datetimefield = '2024-01-01T00:00:00'", 0},
      {"datetimefield IS NULL", 1},
      {"datetimefield IS NOT NULL", 1},
      {"datetimefield IN ('2024-01-01T00:00:00', NULL)", 1},
      {"datetimefield NOT IN ('2024-01-01T00:00:00', NULL)", 0},
      {"datetimefield IN ('1970-01-01T00:00:00', NULL)", 0},
      {"datetimefield NOT IN ('1970-01-01T00:00:00', NULL)", 0}
    };
    return Stream.of(c);
  }

  @Port("autotest/ogr/ogr_sql_test.py::test_ogr_sql_on_null")
  @ParameterizedTest
  @MethodSource("nullCases")
  void onNull(String where, int expected) {
    Dataset m = mem();
    Layer lyr =
        layerNoGeom(
            m,
            "layer",
            FieldDefn.of("intfield", FieldType.INTEGER),
            FieldDefn.of("realfield", FieldType.REAL),
            FieldDefn.of("datetimefield", FieldType.DATETIME),
            FieldDefn.of("strfield", FieldType.STRING));
    lyr.insert(Feature.builder(lyr.type()).build());
    lyr.insert(
        Feature.builder(lyr.type())
            .set("intfield", 1)
            .set("realfield", 1)
            .set("datetimefield", "2024-01-01T00:00:00")
            .set("strfield", "foo")
            .build());
    try (Layer l = m.executeSql("select * from layer where " + where)) {
      assertEquals(expected, l.count(), where);
    }
    assertEquals(expected, lyr.count(Query.sql(where)), where);
    assertThrows(
        JdalException.class, () -> lyr.count(Query.sql("datetimefield IN ('invalid', NULL)")));
  }

  @Port("autotest/ogr/ogr_sql_test.py::test_ogr_sql_identifier_hidden")
  @Test
  void hiddenIsAlsoAnIdentifier() {
    Dataset m = mem();
    Layer lyr = layerNoGeom(m, "hidden", FieldDefn.of("hidden", FieldType.STRING));
    lyr.insert(Feature.builder(lyr.type()).set(0, "val").build());
    for (String sql :
        List.of(
            "SELECT hidden FROM hidden",
            "SELECT hidden hidden FROM hidden hidden",
            "SELECT hidden AS hidden FROM hidden AS hidden")) {
      try (Layer l = m.executeSql(sql)) {
        assertEquals("val", all(l).getFirst().get("hidden"), sql);
      }
    }
    try (Layer l = m.executeSql("SELECT 'foo' AS hidden FROM hidden")) {
      assertEquals("foo", all(l).getFirst().get("hidden"));
    }
    assertTrue(
        assertThrows(
                JdalException.class,
                () -> m.executeSql("SELECT 'foo' AS not_OGR_STYLE HIDDEN FROM hidden"))
            .getMessage()
            .contains("HIDDEN keyword only supported on a column named OGR_STYLE"));
  }

  static Stream<Object[]> kahan() {
    return Stream.of(
        new Object[] {new double[] {1, 1e100, 1, -1e100}, 2.0},
        new Object[] {new double[] {Double.POSITIVE_INFINITY, 1}, Double.POSITIVE_INFINITY},
        new Object[] {new double[] {1, Double.NEGATIVE_INFINITY}, Double.NEGATIVE_INFINITY},
        new Object[] {new double[] {1, Double.NaN}, Double.NaN},
        new Object[] {
          new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}, Double.NaN
        });
  }

  @Port("autotest/ogr/ogr_sql_test.py::test_ogr_sql_kahan_babuska_eumaier_summation")
  @ParameterizedTest
  @MethodSource("kahan")
  void accurateSummation(double[] input, double expected) {
    Dataset m = mem();
    Layer lyr = layerNoGeom(m, "test", FieldDefn.of("v", FieldType.REAL));
    for (double v : input) lyr.insert(Feature.builder(lyr.type()).set(0, v).build());
    try (Layer l = m.executeSql("SELECT SUM(v) FROM test")) {
      double got = (Double) all(l).getFirst().get("SUM_v");
      if (Double.isNaN(expected)) assertTrue(Double.isNaN(got));
      else assertEquals(expected, got);
    }
  }

  @Port({
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_max_expr_depth",
    "autotest/ogr/ogr_sql_test.py::test_ogr_sql_max_expr_depth_other"
  })
  @Test
  void maximumExpressionDepth() {
    Dataset m = mem();
    layerNoGeom(m, "test");
    for (String op :
        List.of("+", "-", "*", "/", "%", "<", "<=", "=<", "=", "<>", ">", ">=", "=>")) {
      try (Layer l =
          m.executeSql(
              "SELECT "
                  + String.join(op, java.util.Collections.nCopies(127, "1"))
                  + " FROM test")) {
        assertNotNull(l);
      }
      assertTrue(
          assertThrows(
                  JdalException.class,
                  () ->
                      m.executeSql(
                          "SELECT "
                              + String.join(op, java.util.Collections.nCopies(128, "1"))
                              + " FROM test"))
              .getMessage()
              .contains("Maximum expression depth reached"),
          op);
    }
    String plus126 = String.join("+", java.util.Collections.nCopies(126, "1")),
        plus127 = String.join("+", java.util.Collections.nCopies(127, "1"));
    String plus125 = String.join("+", java.util.Collections.nCopies(125, "1"));
    Object[][] cases = {
      {"SELECT CAST(%s AS CHARACTER) FROM test", 126, 127},
      {"SELECT 'a' IN (CAST(%s AS CHARACTER)) FROM test", 125, 126},
      {"SELECT NOT %s FROM test", 126, 127},
      {"SELECT 1 AND %s FROM test", 126, 127},
      {"SELECT 1 OR %s FROM test", 126, 127},
      {"SELECT %s IS NULL FROM test", 126, 127},
      {"SELECT %s IS NOT NULL FROM test", 125, 126},
      {"SELECT SUBSTR('a', %s) FROM test", 126, 127}
    };
    for (Object[] c : cases) {
      String ok =
          String.format(
              (String) c[0], String.join("+", java.util.Collections.nCopies((Integer) c[1], "1")));
      String bad =
          String.format(
              (String) c[0], String.join("+", java.util.Collections.nCopies((Integer) c[2], "1")));
      try (Layer l = m.executeSql(ok)) {
        assertNotNull(l);
      }
      assertTrue(
          assertThrows(JdalException.class, () -> m.executeSql(bad))
              .getMessage()
              .contains("Maximum expression depth reached"),
          (String) c[0]);
    }
  }
}
