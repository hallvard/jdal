package dev.jdal.driver.geojson;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.*;
import dev.jdal.feature.*;
import dev.jdal.geom.*;
import dev.jdal.srs.SpatialReference;
import dev.jdal.testkit.Autotest;
import dev.jdal.testkit.Port;
import dev.jdal.vsi.Vsi;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GeoJsonTest {

  String dir = Autotest.vsimem();

  /** Port of {@code validate_layer} in ogr_geojson.py; {@code box} is (minx, maxx, miny, maxy). */
  static void validateLayer(
      Layer lyr, String name, long features, GeometryType typ, int fields, double[] box) {
    assertEquals(name, lyr.name(), "layer name");
    assertEquals(features, lyr.count(), "feature count");
    assertEquals(typ, lyr.type().geometryType(), "geometry type");
    assertEquals(fields, lyr.type().fields().size(), "field count");
    Envelope e = lyr.extent().orElseThrow();
    assertEquals(box[0], e.minX(), 0.0001);
    assertEquals(box[1], e.maxX(), 0.0001);
    assertEquals(box[2], e.minY(), 0.0001);
    assertEquals(box[3], e.maxY(), 0.0001);
  }

  static Stream<Arguments> simpleFiles() {
    return Stream.of(
        Arguments.of(
            "point", GeometryType.POINT, new double[] {100, 100, 0, 0}, "test_ogr_geojson_2"),
        Arguments.of(
            "linestring",
            GeometryType.LINESTRING,
            new double[] {100, 101, 0, 1},
            "test_ogr_geojson_3"),
        Arguments.of(
            "polygon", GeometryType.POLYGON, new double[] {100, 101, 0, 1}, "test_ogr_geojson_4"),
        Arguments.of(
            "geometrycollection",
            GeometryType.GEOMETRYCOLLECTION,
            new double[] {100, 102, 0, 1},
            "test_ogr_geojson_5"),
        Arguments.of(
            "multipoint",
            GeometryType.MULTIPOINT,
            new double[] {100, 101, 0, 1},
            "test_ogr_geojson_6"),
        Arguments.of(
            "multilinestring",
            GeometryType.MULTILINESTRING,
            new double[] {100, 103, 0, 3},
            "test_ogr_geojson_7"),
        Arguments.of(
            "multipolygon",
            GeometryType.MULTIPOLYGON,
            new double[] {100, 103, 0, 3},
            "test_ogr_geojson_8"));
  }

  @Port({
    "autotest/ogr/ogr_geojson.py::test_ogr_geojson_2",
    "autotest/ogr/ogr_geojson.py::test_ogr_geojson_3",
    "autotest/ogr/ogr_geojson.py::test_ogr_geojson_4",
    "autotest/ogr/ogr_geojson.py::test_ogr_geojson_5",
    "autotest/ogr/ogr_geojson.py::test_ogr_geojson_6",
    "autotest/ogr/ogr_geojson.py::test_ogr_geojson_7",
    "autotest/ogr/ogr_geojson.py::test_ogr_geojson_8"
  })
  @ParameterizedTest(name = "{0}")
  @MethodSource("simpleFiles")
  void readSimpleGeometryFiles(String name, GeometryType type, double[] extent, String pyTest) {
    Path f = Autotest.data("ogr/data/geojson/" + name + ".geojson");
    try (Dataset ds = Jdal.open(f.toString())) {
      assertEquals(1, ds.layers().size());
      Layer lyr = ds.layer(name).orElseThrow();
      validateLayer(lyr, name, 1, type, 0, extent);
    }
  }

  @Port("autotest/ogr/ogr_geojson.py::test_ogr_geojson_11")
  @Test
  void layerCrsFromCrsMember() {
    // The per-geometry CRS check of the Python test is a known gap (we keep one CRS per layer).
    try (Dataset ds = Jdal.open(Autotest.data("ogr/data/geojson/srs_name.geojson").toString())) {
      Layer lyr = ds.layer("srs_name").orElseThrow();
      validateLayer(
          lyr, "srs_name", 1, GeometryType.GEOMETRYCOLLECTION, 0, new double[] {100, 102, 0, 1});
      assertEquals(26915, lyr.type().srs().epsg());
    }
  }

  @Port("autotest/ogr/ogr_geojson.py::test_ogr_geojson_37")
  @Test
  void booleanSubtypesAndInlineText() {
    String json =
        """
        {"type": "FeatureCollection","features": [
        { "type": "Feature", "properties": { "bool" : false, "not_bool": false, "bool_list" : [false, true], "notbool_list" : [false, 3]}, "geometry": null  },
        { "type": "Feature", "properties": { "bool" : true, "not_bool": 2, "bool_list" : [true] }, "geometry": null },
        ] }\
        """;
    try (Dataset ds = Jdal.open(json)) {
      Layer lyr = ds.layers().getFirst();
      FeatureType t = lyr.type();
      FieldDefn bool = t.fields().get(t.requireFieldIndex("bool"));
      assertEquals(FieldType.INTEGER, bool.type());
      assertEquals(FieldSubType.BOOLEAN, bool.subType());
      assertEquals(FieldSubType.NONE, t.fields().get(t.requireFieldIndex("not_bool")).subType());
      FieldDefn boolList = t.fields().get(t.requireFieldIndex("bool_list"));
      assertEquals(FieldType.INTEGER_LIST, boolList.type());
      assertEquals(FieldSubType.BOOLEAN, boolList.subType());
      FieldDefn notBoolList = t.fields().get(t.requireFieldIndex("notbool_list"));
      assertEquals(FieldType.STRING, notBoolList.type());
      assertEquals(FieldSubType.JSON, notBoolList.subType());
      Feature f = lyr.features().findFirst().orElseThrow();
      assertEquals(0, f.get("bool"));
      assertEquals(List.of(0, 1), f.get("bool_list"));
      assertEquals("[false,3]", f.get("notbool_list"));
    }
  }

  @Test
  void writeThenReadRoundTrip() {
    FeatureType type =
        new FeatureType(
            "roundtrip",
            List.of(
                FieldDefn.of("n", FieldType.INTEGER),
                FieldDefn.of("big", FieldType.INTEGER64),
                FieldDefn.of("r", FieldType.REAL),
                FieldDefn.of("s", FieldType.STRING),
                FieldDefn.of("flag", FieldType.INTEGER, FieldSubType.BOOLEAN),
                FieldDefn.of("d", FieldType.DATE),
                FieldDefn.of("l", FieldType.INTEGER_LIST),
                FieldDefn.of("j", FieldType.STRING, FieldSubType.JSON)),
            List.of(
                new GeomFieldDefn(
                    "", GeometryType.UNKNOWN, Dimension.XYZ, SpatialReference.WGS84, true)));
    String out = dir + "/out.geojson";
    try (Dataset ds = Jdal.create("GeoJSON", out, Map.of())) {
      Layer l = ds.createLayer(type, Map.of());
      l.insert(
          Feature.builder(type)
              .set("n", 1)
              .set("big", 5_000_000_000L)
              .set("r", 2.5)
              .set("s", "héllo \"x\"")
              .set("flag", 1)
              .set("d", "2024-02-29")
              .set("l", List.of(1, 2))
              .set("j", "{\"a\":[1,2]}")
              .geometry(Wkt.read("POINT Z (1 2 3)"))
              .build());
      l.insert(
          Feature.builder(type).geometry(Wkt.read("POLYGON ((0 0 1,1 0 1,1 1 1,0 0 1))")).build());
    }
    try (Dataset ds = Jdal.open(out)) {
      Layer l = ds.layer("roundtrip").orElseThrow();
      assertEquals(2, l.count());
      FeatureType t = l.type();
      assertEquals(FieldType.INTEGER64, t.fields().get(t.requireFieldIndex("big")).type());
      assertEquals(FieldType.DATE, t.fields().get(t.requireFieldIndex("d")).type());
      assertEquals(FieldSubType.JSON, t.fields().get(t.requireFieldIndex("j")).subType());
      assertEquals(FieldSubType.BOOLEAN, t.fields().get(t.requireFieldIndex("flag")).subType());
      Feature f = l.feature(0).orElseThrow();
      assertEquals(5_000_000_000L, f.get("big"));
      assertEquals("héllo \"x\"", f.get("s"));
      assertEquals(java.time.LocalDate.of(2024, 2, 29), f.get("d"));
      assertEquals(List.of(1, 2), f.get("l"));
      assertEquals("{\"a\":[1,2]}", f.get("j"));
      assertEquals(Wkt.read("POINT Z (1 2 3)"), f.geometry());
      assertNull(l.feature(1).orElseThrow().get("n"));
    }
    assertDoesNotThrow(() -> Vsi.global().readString(out));
  }

  @Test
  void writesNonWgs84Crs() throws Exception {
    FeatureType type =
        FeatureType.of("utm", GeometryType.POINT, SpatialReference.fromEpsg(32633), List.of());
    String out = dir + "/" + "utm.geojson";
    try (Dataset ds = Jdal.create("GeoJSON", out.toString(), Map.of())) {
      ds.createLayer(type, Map.of())
          .insert(Feature.builder(type).geometry(Point.xy(500000, 0)).build());
    }
    assertTrue(Vsi.global().readString(out).contains("urn:ogc:def:crs:EPSG::32633"));
    try (Dataset ds = Jdal.open(out.toString())) {
      assertEquals(32633, ds.layers().getFirst().type().srs().epsg());
    }
  }

  @Test
  void streamsLargeFilesAndWritesStrictJson() throws Exception {
    String in = dir + "/" + "big.geojson";
    StringBuilder sb = new StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[\n");
    int n = 30000;
    for (int i = 0; i < n; i++) {
      sb.append("{\"type\":\"Feature\",\"properties\":{\"v\":")
          .append(i % 7 == 0 ? "NaN" : Integer.toString(i))
          .append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
          .append(i)
          .append(",")
          .append(i % 90)
          .append("]}},\n");
    }
    sb.append("],\"name\":\"big\"}\n"); // trailing comma and a member after the features
    Vsi.global().writeString(in, sb.toString());
    String out = dir + "/" + "out.geojson";
    try (Dataset ds = Jdal.open(in.toString())) {
      Layer l = ds.layers().getFirst();
      assertEquals("big", l.name());
      assertEquals(n, l.count());
      assertEquals(n - 1, l.extent().orElseThrow().maxX());
      assertEquals(FieldType.REAL, l.type().fields().getFirst().type());
      try (Stream<Feature> s = l.features(Query.bbox(new Envelope(100, 0, 102, 90)))) {
        assertEquals(3, s.count());
      }
      try (Dataset dst = Jdal.create("GeoJSON", out.toString(), Map.of())) {
        Layer w = dst.createLayer(l.type(), Map.of());
        try (Stream<Feature> s = l.features()) {
          s.forEach(f -> w.insert(new Feature(w.type(), f.fid(), f.values(), f.geometries())));
        }
      }
    }
    String written = Vsi.global().readString(out);
    assertFalse(written.contains("NaN"), "NaN must not be written");
    assertTrue(written.contains("\"properties\":{\"v\":null}"));
    try (Dataset ds = Jdal.open(out.toString())) {
      assertEquals(n, ds.layers().getFirst().count());
    }
  }

  @Test
  void rejectsNonGeoJson() throws Exception {
    String f = dir + "/" + "x.geojson";
    Vsi.global().writeString(f, "{\"type\": \"Banana\"}");
    assertThrows(JdalException.class, () -> Jdal.open(f.toString()));
    Vsi.global()
        .writeString(f, "{\"type\": \"FeatureCollection\", \"features\": [ {\"type\":\"Feature\",");
    assertThrows(JdalException.class, () -> Jdal.open(f.toString()));
  }
}
