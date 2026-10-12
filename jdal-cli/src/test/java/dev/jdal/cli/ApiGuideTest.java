package dev.jdal.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.Dataset;
import dev.jdal.core.Diagnostics;
import dev.jdal.core.Jdal;
import dev.jdal.core.Layer;
import dev.jdal.core.Query;
import dev.jdal.core.SpatialOp;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldType;
import dev.jdal.geom.Envelope;
import dev.jdal.geom.GeometryType;
import dev.jdal.geom.Point;
import dev.jdal.geom.Wkt;
import dev.jdal.srs.CoordinateTransformation;
import dev.jdal.srs.SpatialReference;
import dev.jdal.utils.Translate;
import dev.jdal.utils.TranslateOptions;
import dev.jdal.vsi.Vsi;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The code of the usage guide (.claude/skills/jdal/SKILL.md), so that the examples stay correct.
 */
class ApiGuideTest {

  @TempDir Path dir;

  private String createCities() {
    String path = dir.resolve("cities.gpkg").toString();
    FeatureType type =
        FeatureType.of(
            "cities",
            GeometryType.POINT,
            SpatialReference.fromEpsg(4326),
            List.of(
                FieldDefn.of("name", FieldType.STRING), FieldDefn.of("pop", FieldType.INTEGER)));
    try (Dataset ds = Jdal.create("GPKG", path, Map.of())) {
      Layer layer = ds.createLayer(type, Map.of());
      layer.insert(
          Feature.builder(layer.type())
              .set("name", "Oslo")
              .set("pop", 700000)
              .geometry(Point.xy(10.75, 59.91))
              .build());
      layer.insert(
          Feature.builder(layer.type())
              .set("name", "Bergen")
              .set("pop", 285000)
              .geometry(Point.xy(5.32, 60.39))
              .build());
      layer.insert(
          Feature.builder(layer.type())
              .set("name", "Alta")
              .set("pop", 20000)
              .geometry(Point.xy(23.27, 69.97))
              .build());
    }
    return path;
  }

  @Test
  void readFilterAndQuery() {
    String path = createCities();
    try (Dataset ds = Jdal.open(path)) {
      Layer layer = ds.requireLayer("cities");
      assertEquals(
          List.of("name:String", "pop:Integer"),
          layer.type().fields().stream()
              .map(f -> f.name() + ":" + f.type().displayName())
              .toList());

      // attribute filter (OGR SQL WHERE syntax), bounding box and limit in one query
      Query q = Query.sql("pop > 100000").withBbox(new Envelope(0, 55, 15, 65)).withLimit(10);
      try (Stream<Feature> s = layer.features(q)) {
        assertEquals(List.of("Oslo", "Bergen"), s.map(f -> (String) f.get("name")).toList());
      }
      // a polygon: exact intersection (jdal-ops)
      Query inPolygon =
          Query.spatial(
              SpatialOp.INTERSECTS, Wkt.read("POLYGON ((0 58, 12 58, 12 62, 0 62, 0 58))"));
      assertEquals(2, layer.count(inPolygon));
      assertEquals(3, layer.count());
      assertEquals(
          "POINT (10.75 59.91)",
          layer.features(Query.sql("name = 'Oslo'")).findFirst().orElseThrow().geometry().toWkt());
    }
  }

  @Test
  void sql() {
    String path = createCities();
    try (Dataset ds = Jdal.open(path)) {
      // GeoPackage and PostGIS run the statement in the database; "OGRSQL" selects the generic
      // engine
      try (Layer r =
          ds.executeSql("SELECT name, pop FROM cities WHERE pop > 100000 ORDER BY pop DESC")) {
        assertEquals(
            List.of("Oslo", "Bergen"), r.features().map(f -> (String) f.get("name")).toList());
      }
      try (Layer r =
          ds.executeSql("SELECT name, OGR_GEOM_AREA FROM cities WHERE name LIKE 'B%'", "OGRSQL")) {
        assertEquals(1, r.count());
      }
      try (Layer r = ds.executeSql("SELECT count(*) FROM cities", "OGRSQL")) {
        assertEquals(3, ((Number) r.features().findFirst().orElseThrow().get(0)).intValue());
      }
    }
  }

  @Test
  void updateAndDelete() {
    String path = createCities();
    try (Dataset ds = Jdal.open(path, true, Map.of())) { // true: update mode
      Layer layer = ds.requireLayer("cities");
      Feature oslo = layer.features(Query.sql("name = 'Oslo'")).findFirst().orElseThrow();
      layer.update(oslo.with("pop", 710000));
      assertTrue(
          layer.delete(layer.features(Query.sql("name = 'Alta'")).findFirst().orElseThrow().fid()));
    }
    try (Dataset ds = Jdal.open(path)) {
      Layer layer = ds.requireLayer("cities");
      assertEquals(2, layer.count());
      assertEquals(
          710000, layer.features(Query.sql("name = 'Oslo'")).findFirst().orElseThrow().get("pop"));
    }
  }

  @Test
  void convertWithTheApi() {
    String path = createCities();
    String out = dir.resolve("cities.geojson").toString();
    long n =
        Translate.translate(
            path,
            out,
            TranslateOptions.defaults()
                .withDriver("GeoJSON")
                .withTargetSrs(SpatialReference.fromEpsg(25833))
                .withWhere(f -> ((Integer) f.get("pop")) > 100000)
                .withLimit(5));
    assertEquals(2, n);
    try (Dataset ds = Jdal.open(out)) {
      Point oslo = (Point) ds.layers().getFirst().features().findFirst().orElseThrow().geometry();
      assertEquals(262410, oslo.x(), 100); // UTM 33N, central meridian 15 east
    }
  }

  @Test
  void crsAndGeometry() {
    var toUtm =
        CoordinateTransformation.between(
            SpatialReference.fromEpsg(4326), SpatialReference.fromEpsg(25833));
    Point p = (Point) toUtm.transform(Point.xy(10.75, 59.91));
    assertEquals(262410, p.x(), 100);
    assertEquals("POINT (1 2)", Wkt.read("POINT(1 2)").toWkt());
    assertEquals(new Envelope(0, 0, 2, 1), Wkt.read("LINESTRING (0 0, 2 1)").envelope());
  }

  @Test
  void virtualFilesAndWarnings() throws Exception {
    Vsi.global().writeString("/vsimem/guide_cities.csv", "name,pop\nOslo,700000\nBergen,285000\n");
    try (Dataset ds =
        Jdal.open("/vsimem/guide_cities.csv", false, Map.of("AUTODETECT_TYPE", "YES"))) {
      assertEquals(2, ds.layers().getFirst().count());
      assertEquals(FieldType.INTEGER, ds.layers().getFirst().type().fields().get(1).type());
    }
    List<String> warnings =
        Diagnostics.capture(
            () ->
                Jdal.open("/vsimem/guide_cities.csv", false, Map.of("SEPARATOR", "SEMICOLON"))
                    .close());
    assertNotNull(warnings);
    Vsi.global().delete("/vsimem/guide_cities.csv");
  }
}
