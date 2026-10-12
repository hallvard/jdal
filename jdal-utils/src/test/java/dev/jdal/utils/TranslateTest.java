package dev.jdal.utils;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.Dataset;
import dev.jdal.core.Jdal;
import dev.jdal.core.Layer;
import dev.jdal.core.memory.MemoryDataset;
import dev.jdal.feature.*;
import dev.jdal.geom.*;
import dev.jdal.srs.SpatialReference;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranslateTest {

  static final FeatureType TYPE =
      FeatureType.of(
          "cities",
          GeometryType.POINT,
          SpatialReference.WGS84,
          List.of(FieldDefn.of("name", FieldType.STRING), FieldDefn.of("pop", FieldType.INTEGER)));

  static Dataset source() {
    MemoryDataset ds = new MemoryDataset("src");
    Layer l = ds.createLayer(TYPE, Map.of());
    l.insert(
        Feature.builder(TYPE).set("name", "A").set("pop", 10).geometry(Point.xy(15, 0)).build());
    l.insert(
        Feature.builder(TYPE).set("name", "B").set("pop", 20).geometry(Point.xy(16, 1)).build());
    l.insert(
        Feature.builder(TYPE).set("name", "C").set("pop", 30).geometry(Point.xy(-50, 40)).build());
    return ds;
  }

  @Test
  void copiesWithFiltersAndRename() {
    try (Dataset src = source();
        Dataset dst = new MemoryDataset("dst")) {
      long n =
          Translate.translate(
              src,
              dst,
              TranslateOptions.defaults()
                  .withBbox(new Envelope(0, -10, 20, 10))
                  .withNewLayerName("near")
                  .withWhere(f -> ((Integer) f.get("pop")) > 10));
      assertEquals(1, n);
      Layer l = dst.requireLayer("near");
      assertEquals("B", l.features().findFirst().orElseThrow().get("name"));
    }
  }

  @Test
  void copiesFeaturesInARelationToAPolygon() {
    FeatureType lines =
        FeatureType.of(
            "lines",
            GeometryType.LINESTRING,
            SpatialReference.WGS84,
            List.of(FieldDefn.of("name", FieldType.STRING)));
    try (MemoryDataset src = new MemoryDataset("src");
        Dataset dst = new MemoryDataset("dst")) {
      Layer l = src.createLayer(lines, Map.of());
      // the second line's envelope overlaps the square but the line does not touch it
      l.insert(
          Feature.builder(lines)
              .set("name", "crossing")
              .geometry(Wkt.read("LINESTRING (-5 5, 15 5)"))
              .build());
      l.insert(
          Feature.builder(lines)
              .set("name", "diagonal")
              .geometry(Wkt.read("LINESTRING (-5 0, 5 -5)"))
              .build());
      Geometry square = Wkt.read("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))");
      long n =
          Translate.translate(
              src,
              dst,
              TranslateOptions.defaults()
                  .withSpatialFilter(
                      new dev.jdal.core.SpatialFilter(dev.jdal.core.SpatialOp.INTERSECTS, square)));
      assertEquals(1, n);
      assertEquals(
          "crossing", dst.requireLayer("lines").features().findFirst().orElseThrow().get("name"));
    }
  }

  @Test
  void reprojects() {
    try (Dataset src = source();
        Dataset dst = new MemoryDataset("dst")) {
      Translate.translate(
          src, dst, TranslateOptions.defaults().withTargetSrs(SpatialReference.fromEpsg(32633)));
      Layer l = dst.requireLayer("cities");
      assertEquals(32633, l.type().srs().epsg());
      Point p = (Point) l.feature(0).orElseThrow().geometry();
      assertEquals(500000, p.x(), 1e-3);
    }
  }

  @Test
  void writesGeoJsonAndInfoDescribesIt(@TempDir Path dir) {
    String out = dir.resolve("cities.geojson").toString();
    try (Dataset src = source()) {
      // memory source cannot be opened by name, so go through the dataset overload
      try (Dataset dst = Jdal.create("GeoJSON", out, Map.of())) {
        assertEquals(3, Translate.translate(src, dst, TranslateOptions.defaults()));
      }
    }
    try (Dataset ds = Jdal.open(out)) {
      String text = Info.describe(ds, false, true);
      assertTrue(text.contains("Layer name: cities"), text);
      assertTrue(text.contains("Feature Count: 3"), text);
      assertTrue(text.contains("Extent: (-50.000000, 0.000000) - (16.000000, 40.000000)"), text);
      assertTrue(text.contains("  name (String) = B"), text);
      assertTrue(text.contains("  POINT (16 1)"), text);
      assertTrue(Info.describe(ds, false, false).contains("1: cities (Point)"));
    }
  }
}
