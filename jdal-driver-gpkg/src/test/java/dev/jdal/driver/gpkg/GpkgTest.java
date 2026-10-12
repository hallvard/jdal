package dev.jdal.driver.gpkg;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.*;
import dev.jdal.feature.*;
import dev.jdal.geom.*;
import dev.jdal.srs.SpatialReference;
import dev.jdal.testkit.Autotest;
import dev.jdal.testkit.Port;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpkgTest {

  @Port("autotest/ogr/ogr_gpkg.py::test_ogr_gpkg_3")
  @Test
  void readsGdalWrittenPolyGolden() {
    Path f = Autotest.data("ogr/data/gpkg/poly_golden.gpkg");
    try (Dataset ds = Jdal.open(f.toString())) {
      assertEquals("GPKG", ds.driver().name());
      Layer lyr = ds.requireLayer("poly");
      assertEquals(GeometryType.POLYGON, lyr.type().geometryType());
      assertEquals(27700, lyr.type().srs().epsg());
      assertEquals(10, lyr.count());
      assertEquals(
          List.of("AREA", "EAS_ID", "PRFEDEA"),
          lyr.type().fields().stream().map(FieldDefn::name).toList());
      List<Long> ids = new ArrayList<>();
      try (Stream<Feature> s = lyr.features()) {
        s.forEach(feat -> ids.add((Long) feat.get("EAS_ID")));
      }
      assertEquals(List.of(168L, 179L, 171L, 173L, 172L, 169L, 166L, 158L, 165L, 170L), ids);
      Envelope e = lyr.extent().orElseThrow();
      assertEquals(478315.53125, e.minX(), 1e-6);
      assertEquals(4765610.5, e.maxY(), 1e-6);
      Feature first = lyr.feature(0).or(() -> lyr.features().findFirst()).orElseThrow();
      assertInstanceOf(Polygon.class, first.geometry());
    }
  }

  @Port({
    "autotest/ogr/ogr_gpkg.py::test_ogr_gpkg_envelope_2d",
    "autotest/ogr/ogr_gpkg.py::test_ogr_gpkg_envelope_3d"
  })
  @Test
  void geometryHeaderVariantsDecode() {
    for (String n : List.of("2d_envelope", "3d_envelope", "no_envelope")) {
      try (Dataset ds = Jdal.open(Autotest.data("ogr/data/gpkg/" + n + ".gpkg").toString())) {
        Layer lyr = ds.requireLayer("foo");
        assertEquals(2, lyr.count(), n);
        lyr.features().forEach(feat -> assertInstanceOf(LineString.class, feat.geometry(), n));
      }
    }
  }

  @Test
  void nullFirstGeometry() {
    try (Dataset ds =
        Jdal.open(Autotest.data("ogr/data/gpkg/first_geometry_null.gpkg").toString())) {
      List<Feature> fs = ds.layers().getFirst().features().toList();
      assertEquals(2, fs.size());
      assertNull(fs.getFirst().geometry());
      assertNotNull(fs.get(1).geometry());
    }
  }

  @Test
  void roundTripAllFieldTypes(@TempDir Path dir) {
    FeatureType t =
        new FeatureType(
            "things",
            List.of(
                FieldDefn.of("i", FieldType.INTEGER),
                FieldDefn.of("l", FieldType.INTEGER64),
                FieldDefn.of("r", FieldType.REAL),
                FieldDefn.of("s", FieldType.STRING).withWidth(20, 0),
                FieldDefn.of("flag", FieldType.INTEGER, FieldSubType.BOOLEAN),
                FieldDefn.of("small", FieldType.INTEGER, FieldSubType.INT16),
                FieldDefn.of("bin", FieldType.BINARY),
                FieldDefn.of("d", FieldType.DATE),
                FieldDefn.of("ts", FieldType.DATETIME),
                FieldDefn.of("j", FieldType.STRING, FieldSubType.JSON),
                FieldDefn.of("nn", FieldType.STRING).withNullable(false)),
            List.of(
                new GeomFieldDefn(
                    "shape",
                    GeometryType.UNKNOWN,
                    Dimension.XYZ,
                    SpatialReference.fromEpsg(32633),
                    true)));
    String path = dir.resolve("t.gpkg").toString();
    try (Dataset ds = Jdal.create("GPKG", path, Map.of())) {
      Layer l = ds.createLayer(t, Map.of());
      l.insert(
          Feature.builder(l.type())
              .set("i", 7)
              .set("l", 1L << 40)
              .set("r", 0.1)
              .set("s", "ærlig")
              .set("flag", 1)
              .set("small", 3)
              .set("bin", new byte[] {1, 2, 3})
              .set("d", "2024-02-29")
              .set("ts", "2024-02-29T12:30:45.5Z")
              .set("j", "{\"a\":1}")
              .set("nn", "x")
              .geometry(Wkt.read("POINT Z (500000 1 5)"))
              .build());
      l.insert(
          Feature.builder(l.type())
              .set("nn", "y")
              .geometry(Wkt.read("LINESTRING Z (0 0 0,10 10 10)"))
              .build());
      l.insert(Feature.builder(l.type()).fid(100).set("nn", "z").build());
    }
    try (Dataset ds = Jdal.open(path)) {
      assertEquals("GPKG", ds.driver().name());
      Layer l = ds.requireLayer("things");
      assertEquals(3, l.count());
      FeatureType rt = l.type();
      assertEquals(FieldType.INTEGER, rt.fields().get(0).type());
      assertEquals(FieldType.INTEGER64, rt.fields().get(1).type());
      assertEquals(20, rt.fields().get(3).width());
      assertEquals(FieldSubType.BOOLEAN, rt.fields().get(4).subType());
      assertEquals(FieldSubType.INT16, rt.fields().get(5).subType());
      assertEquals(FieldType.DATE, rt.fields().get(7).type());
      assertEquals(FieldType.DATETIME, rt.fields().get(8).type());
      assertEquals(FieldSubType.JSON, rt.fields().get(9).subType());
      assertFalse(rt.fields().get(10).nullable());
      assertEquals(32633, rt.srs().epsg());
      assertEquals("shape", rt.geomFields().getFirst().name());
      assertEquals(Dimension.XYZ, rt.geomFields().getFirst().dimension());

      Feature f = l.feature(1).orElseThrow();
      assertEquals(7, f.get("i"));
      assertEquals(1L << 40, f.get("l"));
      assertEquals(0.1, f.get("r"));
      assertEquals("ærlig", f.get("s"));
      assertEquals(1, f.get("flag"));
      assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) f.get("bin"));
      assertEquals(LocalDate.of(2024, 2, 29), f.get("d"));
      assertEquals(OffsetDateTime.parse("2024-02-29T12:30:45.500Z"), f.get("ts"));
      assertEquals(Wkt.read("POINT Z (500000 1 5)"), f.geometry());
      assertEquals(
          Wkt.read("LINESTRING Z (0 0 0,10 10 10)"), l.feature(2).orElseThrow().geometry());
      assertNull(l.feature(100).orElseThrow().geometry());
      assertEquals(new Envelope(0, 0, 500000, 10), l.extent().orElseThrow());
    }
  }

  @Test
  void updateDeleteAddFieldAndSql(@TempDir Path dir) {
    FeatureType t =
        FeatureType.of(
            "pts",
            GeometryType.POINT,
            SpatialReference.WGS84,
            List.of(FieldDefn.of("name", FieldType.STRING)));
    String path = dir.resolve("e.gpkg").toString();
    try (Dataset ds = Jdal.create("GPKG", path, Map.of())) {
      Layer l = ds.createLayer(t, Map.of());
      for (int i = 0; i < 5; i++) {
        l.insert(Feature.builder(l.type()).set("name", "p" + i).geometry(Point.xy(i, i)).build());
      }
    }
    try (Dataset ds = Jdal.open(path, true, Map.of())) {
      Layer l = ds.requireLayer("pts");
      l.update(l.feature(2).orElseThrow().with("name", "changed")); // fids start at 1
      assertTrue(l.delete(3));
      assertFalse(l.delete(3));
      l.addField(FieldDefn.of("extra", FieldType.REAL));
      l.update(l.feature(4).orElseThrow().with("extra", 2.5));
      Layer r =
          ds.executeSql(
              "SELECT name, extra, geom FROM pts WHERE extra IS NOT NULL OR name = 'changed' ORDER"
                  + " BY fid");
      assertEquals(2, r.count());
      Feature first = r.features().findFirst().orElseThrow();
      assertEquals("changed", first.get("name"));
      assertEquals(Point.xy(1, 1), first.geometry());
      assertNull(ds.executeSql("UPDATE pts SET name = 'sql' WHERE fid = 1"));
    }
    try (Dataset ds = Jdal.open(path)) {
      Layer l = ds.requireLayer("pts");
      assertEquals(4, l.count());
      assertEquals("sql", l.feature(1).orElseThrow().get("name"));
      assertEquals(2.5, l.feature(4).orElseThrow().get("extra"));
      assertEquals(2, l.count(Query.bbox(new Envelope(3, 3, 4, 4))));
      assertThrows(
          JdalException.class,
          () -> {
            try (Dataset ro = Jdal.open(path)) {
              ro.requireLayer("pts").delete(1);
            }
          });
    }
  }

  @Test
  void geoPackageInVsimemSurvivesCloseAndUpdate() {
    FeatureType t =
        FeatureType.of(
            "pts",
            GeometryType.POINT,
            SpatialReference.WGS84,
            List.of(FieldDefn.of("name", FieldType.STRING)));
    String path = Autotest.vsimem() + ".gpkg";
    try (Dataset ds = Jdal.create("GPKG", path, Map.of())) {
      Layer l = ds.createLayer(t, Map.of());
      for (int i = 0; i < 3; i++) {
        l.insert(Feature.builder(l.type()).set("name", "p" + i).geometry(Point.xy(i, i)).build());
      }
    }
    assertTrue(dev.jdal.vsi.Vsi.global().stat(path).orElseThrow().size() > 10_000);
    try (Dataset ds = Jdal.open(path)) { // identified by its header
      assertEquals("GPKG", ds.driver().name());
      assertEquals(3, ds.requireLayer("pts").count());
    }
    try (Dataset ds = Jdal.open(path, true, Map.of())) {
      Layer l = ds.requireLayer("pts");
      l.delete(1);
      l.update(l.feature(2).orElseThrow().with("name", "changed"));
    }
    try (Dataset ds = Jdal.open(path)) {
      Layer l = ds.requireLayer("pts");
      assertEquals(2, l.count());
      assertEquals("changed", l.feature(2).orElseThrow().get("name"));
    }
    // read-only access does not write back
    long size = dev.jdal.vsi.Vsi.global().stat(path).orElseThrow().size();
    try (Dataset ds = Jdal.open(path)) {
      ds.requireLayer("pts").count();
    }
    assertEquals(size, dev.jdal.vsi.Vsi.global().stat(path).orElseThrow().size());
  }
}
