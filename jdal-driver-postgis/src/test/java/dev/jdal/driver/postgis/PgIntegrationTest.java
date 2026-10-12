package dev.jdal.driver.postgis;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.*;
import dev.jdal.feature.*;
import dev.jdal.geom.*;
import dev.jdal.srs.SpatialReference;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Needs a PostGIS database. Set {@code JDAL_TEST_PG} to a connection string, e.g. {@code
 * PG:host=localhost dbname=jdal user=postgres password=postgres}, for example against {@code docker
 * run -p 5432:5432 -e POSTGRES_PASSWORD=postgres postgis/postgis}. Skipped otherwise. The test
 * creates and drops the tables {@code jdal_it_*}.
 */
class PgIntegrationTest {

  static String conn;

  @BeforeAll
  static void connection() {
    conn = System.getenv("JDAL_TEST_PG");
    Assumptions.assumeTrue(conn != null && !conn.isBlank(), "JDAL_TEST_PG not set");
  }

  @Test
  void roundTrip() {
    FeatureType t =
        new FeatureType(
            "jdal_it_things",
            List.of(
                FieldDefn.of("i", FieldType.INTEGER),
                FieldDefn.of("l", FieldType.INTEGER64),
                FieldDefn.of("r", FieldType.REAL),
                FieldDefn.of("s", FieldType.STRING),
                FieldDefn.of("flag", FieldType.INTEGER, FieldSubType.BOOLEAN),
                FieldDefn.of("d", FieldType.DATE),
                FieldDefn.of("ts", FieldType.DATETIME),
                FieldDefn.of("il", FieldType.INTEGER_LIST),
                FieldDefn.of("j", FieldType.STRING, FieldSubType.JSON)),
            List.of(
                new GeomFieldDefn(
                    "geom", GeometryType.UNKNOWN, Dimension.XYZ, SpatialReference.WGS84, true)));
    try (Dataset ds = Jdal.open(conn, true, Map.of())) {
      Layer l = ds.createLayer(t, Map.of("OVERWRITE", "YES"));
      l.insert(
          Feature.builder(l.type())
              .set("i", 1)
              .set("l", 1L << 40)
              .set("r", 0.1)
              .set("s", "ærlig")
              .set("flag", 1)
              .set("d", "2024-02-29")
              .set("ts", "2024-02-29T12:30:45Z")
              .set("il", List.of(1, 2, 3))
              .set("j", "{\"a\": 1}")
              .geometry(Wkt.read("POINT Z (1 2 3)"))
              .build());
      l.insert(
          Feature.builder(l.type()).geometry(Wkt.read("LINESTRING Z (10 10 0,20 20 0)")).build());
    }
    try (Dataset ds = Jdal.open(conn)) {
      Layer l = ds.requireLayer("jdal_it_things");
      assertEquals(2, l.count());
      assertEquals(4326, l.type().srs().epsg());
      Feature f = l.features().findFirst().orElseThrow();
      assertEquals(1, f.get("i"));
      assertEquals(1L << 40, f.get("l"));
      assertEquals("ærlig", f.get("s"));
      assertEquals(1, f.get("flag"));
      assertEquals(List.of(1, 2, 3), f.get("il"));
      assertEquals(Wkt.read("POINT Z (1 2 3)"), f.geometry());
      assertEquals(1, l.count(Query.bbox(new Envelope(0, 0, 5, 5))));
      // exact tests in the database: the line's envelope overlaps this triangle's, the line does
      // not
      Geometry above = Wkt.read("POLYGON ((10 20, 14 20, 10 16, 10 20))");
      assertEquals(0, l.count(Query.spatial(SpatialOp.INTERSECTS, above)));
      assertEquals(2, l.count(Query.spatial(SpatialOp.DISJOINT, above)));
      assertEquals(
          1,
          l.count(
              Query.spatial(SpatialOp.WITHIN, Wkt.read("POLYGON ((0 0, 5 0, 5 5, 0 5, 0 0))"))));
      assertEquals(
          1,
          l.count(
              Query.spatial(
                      SpatialOp.INTERSECTS,
                      Wkt.read("POLYGON ((15 15, 25 15, 25 25, 15 25, 15 15))"))
                  .withLimit(5)));
      assertEquals(new Envelope(1, 2, 20, 20), l.extent().orElseThrow());
      Layer r = ds.executeSql("SELECT i, geom FROM jdal_it_things WHERE i = 1");
      assertEquals(Wkt.read("POINT Z (1 2 3)"), r.features().findFirst().orElseThrow().geometry());
      ds.deleteLayer("jdal_it_things");
    }
  }
}
