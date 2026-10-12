package dev.jdal.driver.shapefile;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.*;
import dev.jdal.feature.*;
import dev.jdal.geom.*;
import dev.jdal.srs.SpatialReference;
import dev.jdal.testkit.Autotest;
import dev.jdal.testkit.Port;
import dev.jdal.vsi.Vsi;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ShapefileTest {

  String dir = Autotest.vsimem();

  static String join(String dir, String name) {
    return dir + "/" + name;
  }

  static void copy(java.nio.file.Path src, String dst) throws Exception {
    Vsi.global().writeBytes(dst, java.nio.file.Files.readAllBytes(src));
  }

  static Dataset open(String relative) {
    return Jdal.open(Autotest.data("ogr/data/" + relative).toString());
  }

  static List<Feature> all(Layer l) {
    try (Stream<Feature> s = l.features()) {
      return s.toList();
    }
  }

  static List<Long> fids(Stream<Feature> s) {
    try (s) {
      return s.map(Feature::fid).toList();
    }
  }

  static Geometry wkt(String s) {
    return Wkt.read(s);
  }

  // ------------------------------------------------------------------ reading GDAL's data

  @Port({"autotest/ogr/ogr_shape.py::test_ogr_shape_2"})
  @Test
  void readsPolyShp() {
    try (Dataset ds = open("poly.shp")) {
      Layer l = ds.requireLayer("poly");
      assertEquals(GeometryType.MULTIPOLYGON, l.type().geometryType());
      assertEquals(10, l.count());
      assertEquals(27700, l.type().srs().epsg());
      assertEquals(
          List.of("AREA", "EAS_ID", "PRFEDEA"),
          l.type().fields().stream().map(FieldDefn::name).toList());
      assertEquals(FieldType.REAL, l.type().fields().get(0).type());
      assertEquals(12, l.type().fields().get(0).width());
      assertEquals(3, l.type().fields().get(0).precision());
      assertEquals(FieldType.INTEGER64, l.type().fields().get(1).type());
      assertEquals(16, l.type().fields().get(2).width());
      assertEquals(
          new Envelope(478315.53125, 4762880.5, 481645.3125, 4765610.5), l.extent().orElseThrow());
      List<Feature> fs = all(l);
      assertEquals(
          List.of(168L, 179L, 171L, 173L, 172L, 169L, 166L, 158L, 165L, 170L),
          fs.stream().map(f -> f.get("EAS_ID")).toList());
      assertEquals(215229.266, fs.getFirst().get("AREA"));
      assertEquals("35043411", fs.getFirst().get("PRFEDEA"));
      assertInstanceOf(MultiPolygon.class, fs.getFirst().geometry());
      assertEquals(
          20, ((MultiPolygon) fs.getFirst().geometry()).parts().getFirst().exteriorRing().size());
    }
  }

  @Port("autotest/ogr/ogr_shape.py::test_ogr_shape_12")
  @Test
  void multipolygonOfAsmShp() {
    try (Dataset ds = open("shp/asm.shp")) {
      Feature f = all(ds.layers().getFirst()).getFirst();
      MultiPolygon mp = (MultiPolygon) f.geometry();
      assertEquals(Dimension.XY, mp.dimension());
      assertEquals(5, mp.parts().size());
      int[] counts = {15, 11, 17, 20, 9};
      for (int i = 0; i < 5; i++) {
        assertEquals(
            1, mp.parts().get(i).rings().size(), "polygon " + i + " has more than one ring");
        assertEquals(counts[i], mp.parts().get(i).exteriorRing().size(), "polygon " + i);
      }
    }
  }

  @Port("autotest/ogr/ogr_shape.py::test_ogr_shape_10")
  @Test
  void spatialFilterOnTestpoly() {
    try (Dataset ds = open("shp/testpoly.shp")) {
      Layer l = ds.layers().getFirst();
      assertEquals(14, l.count());
      assertEquals(
          List.of(0L, 4L, 8L), fids(l.features(Query.bbox(new Envelope(-400, 22, -120, 400)))));
      // the mixed attribute and spatial query of test_ogr_shape_11, with a predicate for FID
      assertEquals(
          List.of(),
          fids(
              l.features(
                  Query.bbox(new Envelope(-400, 22, -120, 400)).withFilter(f -> f.fid() == 5))));
      assertEquals(
          List.of(4L),
          fids(
              l.features(
                  Query.bbox(new Envelope(-400, 22, -120, 400)).withFilter(f -> f.fid() == 4))));
    }
  }

  @Test
  void geometryAndTypeOfSimpleFiles() {
    record Case(String file, GeometryType type, String wkt) {}
    for (Case c :
        List.of(
            new Case("gjpoint", GeometryType.POINT, "POINT (100 0)"),
            new Case("gjline", GeometryType.MULTILINESTRING, "MULTILINESTRING ((100 0,101 1))"),
            new Case("gjmultipoint", GeometryType.MULTIPOINT, "MULTIPOINT ((100 0),(101 1))"))) {
      try (Dataset ds = open("shp/" + c.file() + ".shp")) {
        Layer l = ds.layers().getFirst();
        assertEquals(c.type(), l.type().geometryType(), c.file());
        assertEquals(1, l.count());
        assertEquals(wkt(c.wkt()), all(l).getFirst().geometry(), c.file());
      }
    }
  }

  @Test
  void measuredLinesAndMultipatch() {
    try (Dataset ds = open("shp/arcm_with_m.shp")) {
      Layer l = ds.layers().getFirst();
      assertEquals(Dimension.XYM, l.type().geomFields().getFirst().dimension());
      assertTrue(all(l).getFirst().geometry().dimension().hasM());
    }
    try (Dataset ds = open("shp/arcm_without_m.shp")) {
      assertEquals(Dimension.XY, ds.layers().getFirst().type().geomFields().getFirst().dimension());
    }
    try (Dataset ds = open("shp/multipatch.shp")) {
      JdalException e = assertThrows(JdalException.class, () -> all(ds.layers().getFirst()));
      assertTrue(e.getMessage().contains("Unsupported shape type 31"), e.getMessage());
    }
  }

  @Test
  void emptyShapefileAndDirectories() {
    try (Dataset ds = open("shp/empty.shp")) {
      Layer l = ds.layers().getFirst();
      assertEquals(0, l.count());
      assertTrue(l.extent().isEmpty());
      assertEquals(3, l.type().fields().size());
    }
  }

  @Test
  void dbfOnlyLayerHasNoGeometry() {
    try (Dataset ds = open("flat.dbf")) {
      Layer l = ds.layers().getFirst();
      assertEquals(GeometryType.NONE, l.type().geometryType());
      assertTrue(l.count() > 0);
      assertNull(all(l).getFirst().geometry());
    }
  }

  // ------------------------------------------------------------------ writing (fixture of
  // ogr_shape.py)

  /**
   * Copy of poly.shp into a new shapefile "tpoly" with the schema of the Python fixture, as
   * multipolygons.
   */
  Dataset createTpoly() {
    Dataset ds = Jdal.create("ESRI Shapefile", dir, Map.of());
    FeatureType t =
        new FeatureType(
            "tpoly",
            List.of(
                FieldDefn.of("AREA", FieldType.REAL),
                FieldDefn.of("EAS_ID", FieldType.INTEGER),
                FieldDefn.of("PRFEDEA", FieldType.STRING)),
            List.of(new GeomFieldDefn("", GeometryType.UNKNOWN, Dimension.XY, null, true)));
    Layer out = ds.createLayer(t, Map.of());
    try (Dataset src = open("poly.shp")) {
      for (Feature f : all(src.layers().getFirst())) {
        out.insert(
            Feature.builder(out.type())
                .set("AREA", f.get("AREA"))
                .set("EAS_ID", f.get("EAS_ID"))
                .set("PRFEDEA", f.get("PRFEDEA"))
                .geometry(f.geometry())
                .build());
      }
    }
    return ds;
  }

  @Port({
    "autotest/ogr/ogr_shape.py::test_ogr_shape_3",
    "autotest/ogr/ogr_shape.py::test_ogr_shape_4"
  })
  @Test
  void writtenCopyOfPolyReadsBackIdentically() {
    try (Dataset ds = createTpoly()) {
      Layer l = ds.requireLayer("tpoly");
      l.insert(Feature.builder(l.type()).set("PRFEDEA", "nulled").build());
    }
    try (Dataset ds = Jdal.open(join(dir, "tpoly.shp").toString());
        Dataset src = open("poly.shp")) {
      Layer l = ds.layers().getFirst();
      assertEquals(11, l.count());
      List<Feature> original = all(src.layers().getFirst());
      List<Feature> copy = all(l);
      for (int i = 0; i < original.size(); i++) {
        assertEquals(original.get(i).geometry(), copy.get(i).geometry(), "geometry " + i);
        assertEquals(original.get(i).get("AREA"), copy.get(i).get("AREA"), "area " + i);
        assertEquals(
            ((Number) original.get(i).get("EAS_ID")).intValue(),
            ((Number) copy.get(i).get("EAS_ID")).intValue());
        assertEquals(original.get(i).get("PRFEDEA"), copy.get(i).get("PRFEDEA"));
      }
      assertEquals(
          List.of(168, 169, 166, 158, 165),
          copy.stream()
              .filter(f -> f.get("EAS_ID") != null && ((Number) f.get("EAS_ID")).intValue() < 170)
              .map(f -> ((Number) f.get("EAS_ID")).intValue())
              .toList());
      Feature nulled =
          copy.stream().filter(f -> "nulled".equals(f.get("PRFEDEA"))).findFirst().orElseThrow();
      assertNull(nulled.geometry());
      assertEquals(GeometryType.MULTIPOLYGON, l.type().geometryType()); // promoted on reading
    }
  }

  @Port({
    "autotest/ogr/ogr_shape.py::test_ogr_shape_13",
    "autotest/ogr/ogr_shape.py::test_ogr_shape_15"
  })
  @Test
  void updateAndDeleteFeatures() {
    createTpoly().close();
    String shp = join(dir, "tpoly.shp");
    try (Dataset ds = Jdal.open(shp, true, Map.of())) {
      Layer l = ds.layers().getFirst();
      Feature f9 =
          l.feature(9)
              .orElseThrow()
              .with("AREA", 6000.0)
              .withGeometry(wkt("POLYGON ((0 0, 0 60, 100 60, 100 0, 200 -30, 0 0))"));
      l.update(f9); // bigger: moves to the end of the .shp
      Feature f8 =
          l.feature(8)
              .orElseThrow()
              .with("AREA", 7000.0)
              .withGeometry(wkt("POLYGON ((0 0, 0 60, 100 60, 100 0, 0 0))"));
      l.update(f8); // smaller: in place
    }
    try (Dataset ds = Jdal.open(shp, true, Map.of())) {
      Layer l = ds.layers().getFirst();
      Feature f9 = l.feature(9).orElseThrow();
      assertEquals(6000.0, f9.get("AREA"));
      assertEquals(5, ((MultiPolygon) f9.geometry()).parts().getFirst().exteriorRing().size() - 1);
      Feature f8 = l.feature(8).orElseThrow();
      assertEquals(7000.0, f8.get("AREA"));
      assertEquals(
          wkt("MULTIPOLYGON (((0 0,0 60,100 60,100 0,0 0)))").envelope(), f8.geometry().envelope());
      // delete
      assertEquals(10, l.count());
      assertTrue(l.delete(9));
      assertFalse(l.delete(9));
      assertEquals(9, l.count());
      assertTrue(l.feature(9).isEmpty());
      assertFalse(all(l).stream().anyMatch(f -> f.fid() == 9));
    }
    try (Dataset ds = Jdal.open(shp)) {
      assertEquals(9, ds.layers().getFirst().count());
    }
  }

  @Test
  void appendToExistingShapefile() {
    createTpoly().close();
    String shp = join(dir, "tpoly.shp");
    try (Dataset ds = Jdal.open(shp, true, Map.of())) {
      Layer l = ds.layers().getFirst();
      l.insert(
          Feature.builder(l.type())
              .set("EAS_ID", 999)
              .set("PRFEDEA", "new")
              .geometry(wkt("MULTIPOLYGON (((0 0,0 1,1 1,1 0,0 0)))"))
              .build());
    }
    try (Dataset ds = Jdal.open(shp)) {
      Layer l = ds.layers().getFirst();
      assertEquals(11, l.count());
      assertEquals("new", l.feature(10).orElseThrow().get("PRFEDEA"));
      assertEquals(new Envelope(0, 0, 481645.3125, 4765610.5), l.extent().orElseThrow());
    }
  }

  @Test
  void addFieldRewritesTheTable() {
    createTpoly().close();
    String shp = join(dir, "tpoly.shp");
    try (Dataset ds = Jdal.open(shp, true, Map.of())) {
      Layer l = ds.layers().getFirst();
      l.addField(FieldDefn.of("extra", FieldType.INTEGER));
      l.update(l.feature(2).orElseThrow().with("extra", 42));
    }
    try (Dataset ds = Jdal.open(shp)) {
      Layer l = ds.layers().getFirst();
      assertEquals(4, l.type().fields().size());
      assertEquals(42, l.feature(2).orElseThrow().get("extra"));
      assertEquals(168L, ((Number) l.feature(0).orElseThrow().get("EAS_ID")).longValue());
      assertNull(l.feature(0).orElseThrow().get("extra"));
    }
  }

  // ------------------------------------------------------------------ geometry types written and
  // read back

  void roundTrip(String layer, GeometryType type, Dimension dim, String... wkts) {
    FeatureType t =
        new FeatureType(
            layer,
            List.of(FieldDefn.of("id", FieldType.INTEGER)),
            List.of(new GeomFieldDefn("", type, dim, SpatialReference.WGS84, true)));
    try (Dataset ds =
        Jdal.create("ESRI Shapefile", join(dir, layer + ".shp").toString(), Map.of())) {
      Layer l = ds.createLayer(t, Map.of());
      int i = 0;
      for (String w : wkts)
        l.insert(
            Feature.builder(l.type()).set("id", i++).geometry(w == null ? null : wkt(w)).build());
    }
    try (Dataset ds = Jdal.open(join(dir, layer + ".shp").toString())) {
      Layer l = ds.layers().getFirst();
      List<Feature> fs = all(l);
      assertEquals(wkts.length, fs.size());
      for (int i = 0; i < wkts.length; i++) {
        Geometry expected = wkts[i] == null ? null : wkt(wkts[i]);
        assertEquals(expected, fs.get(i).geometry(), layer + " feature " + i);
      }
      assertEquals(4326, l.type().srs().epsg());
    }
  }

  @Test
  void roundTripsAllShapeTypes() {
    roundTrip("pt", GeometryType.POINT, Dimension.XY, "POINT (1 2)", null, "POINT (3 4)");
    roundTrip("ptz", GeometryType.POINT, Dimension.XYZ, "POINT Z (1 2 3)", "POINT Z (4 5 6)");
    roundTrip("ptzm", GeometryType.POINT, Dimension.XYZM, "POINT ZM (1 2 3 4)");
    roundTrip("ptm", GeometryType.POINT, Dimension.XYM, "POINT M (1 2 3)");
    roundTrip("mpt", GeometryType.MULTIPOINT, Dimension.XY, "MULTIPOINT ((1 2),(3 4))");
    roundTrip("mptz", GeometryType.MULTIPOINT, Dimension.XYZ, "MULTIPOINT Z ((1 2 3),(3 4 5))");
    roundTrip(
        "line",
        GeometryType.MULTILINESTRING,
        Dimension.XY,
        "MULTILINESTRING ((0 0,1 1),(5 5,6 6,7 5))");
    roundTrip(
        "linez", GeometryType.MULTILINESTRING, Dimension.XYZ, "MULTILINESTRING Z ((0 0 1,1 1 2))");
    roundTrip(
        "linem", GeometryType.MULTILINESTRING, Dimension.XYM, "MULTILINESTRING M ((0 0 1,1 1 2))");
    roundTrip(
        "poly",
        GeometryType.MULTIPOLYGON,
        Dimension.XY,
        "MULTIPOLYGON (((0 0,0 10,10 10,10 0,0 0),(2 2,4 2,4 4,2 4,2 2)),((20 20,20 30,30 30,30"
            + " 20,20 20)))");
    roundTrip(
        "polyz",
        GeometryType.MULTIPOLYGON,
        Dimension.XYZ,
        "MULTIPOLYGON Z (((0 0 1,0 10 1,10 10 1,10 0 1,0 0 1)))");
  }

  @Test
  void ringOrientationIsFixedOnWrite() {
    // counter-clockwise shell and clockwise hole as OGC wants them: shapefiles want the opposite
    // written with a clockwise shell and counter-clockwise hole; read back as multipolygon in file
    // orientation
    FeatureType t =
        new FeatureType(
            "orient",
            List.of(),
            List.of(new GeomFieldDefn("", GeometryType.POLYGON, Dimension.XY, null, true)));
    try (Dataset ds = Jdal.create("ESRI Shapefile", join(dir, "orient.shp").toString(), Map.of())) {
      ds.createLayer(t, Map.of())
          .insert(
              Feature.builder(t)
                  .geometry(wkt("POLYGON ((0 0,10 0,10 10,0 10,0 0),(2 2,2 4,4 4,4 2,2 2))"))
                  .build());
    }
    try (Dataset ds = Jdal.open(join(dir, "orient.shp").toString())) {
      assertEquals(
          wkt("MULTIPOLYGON (((0 0,0 10,10 10,10 0,0 0),(2 2,4 2,4 4,2 4,2 2)))"),
          all(ds.layers().getFirst()).getFirst().geometry());
    }
  }

  @Test
  void shapeTypeFollowsTheFirstFeatureIfUndeclared() throws Exception {
    FeatureType t =
        new FeatureType(
            "auto",
            List.of(),
            List.of(new GeomFieldDefn("", GeometryType.UNKNOWN, Dimension.XY, null, true)));
    try (Dataset ds = Jdal.create("ESRI Shapefile", join(dir, "auto.shp").toString(), Map.of())) {
      Layer l = ds.createLayer(t, Map.of());
      l.insert(Feature.builder(l.type()).build()); // no geometry yet
      l.insert(Feature.builder(l.type()).geometry(wkt("LINESTRING (0 0,1 1)")).build());
      JdalException e =
          assertThrows(
              JdalException.class,
              () -> l.insert(Feature.builder(l.type()).geometry(wkt("POINT (1 1)")).build()));
      assertTrue(e.getMessage().contains("Point"), e.getMessage());
    }
    try (Dataset ds = Jdal.open(join(dir, "auto.shp").toString())) {
      assertEquals(GeometryType.MULTILINESTRING, ds.layers().getFirst().type().geometryType());
      assertEquals(2, ds.layers().getFirst().count());
    }
  }

  // ------------------------------------------------------------------ attributes

  @Test
  void attributeTypesLongNamesAndEncoding() throws Exception {
    FeatureType t =
        new FeatureType(
            "attrs",
            List.of(
                FieldDefn.of("a_rather_long_name", FieldType.INTEGER),
                    FieldDefn.of("a_rather_long_nbme", FieldType.INTEGER),
                FieldDefn.of("big", FieldType.INTEGER64),
                    FieldDefn.of("r", FieldType.REAL).withWidth(10, 3),
                FieldDefn.of("flag", FieldType.INTEGER, FieldSubType.BOOLEAN),
                    FieldDefn.of("d", FieldType.DATE),
                FieldDefn.of("s", FieldType.STRING).withWidth(30, 0),
                    FieldDefn.of("l", FieldType.INTEGER_LIST)),
            List.of());
    List<String> warnings =
        Diagnostics.capture(
            () -> {
              try (Dataset ds =
                  Jdal.create("ESRI Shapefile", join(dir, "attrs.dbf").toString(), Map.of())) {
                Layer l = ds.createLayer(t, Map.of("ENCODING", "UTF-8"));
                l.insert(
                    Feature.builder(l.type())
                        .set(0, 7)
                        .set(1, -8)
                        .set(2, 5_000_000_000L)
                        .set(3, 1.5)
                        .set(4, 1)
                        .set(5, "2024-02-29")
                        .set(6, "æøå ✓")
                        .set(7, List.of(1, 2))
                        .build());
              }
            });
    assertEquals(
        2, warnings.stream().filter(w -> w.contains("laundered")).count(), warnings.toString());
    assertEquals("UTF-8", Vsi.global().readString(join(dir, "attrs.cpg")));
    try (Dataset ds = Jdal.open(join(dir, "attrs.dbf").toString())) {
      Layer l = ds.layers().getFirst();
      assertEquals(
          List.of("a_rather_l", "a_rather_1", "big", "r", "flag", "d", "s", "l"),
          l.type().fields().stream().map(FieldDefn::name).toList());
      Feature f = all(l).getFirst();
      assertEquals(7, f.get(0));
      assertEquals(-8, f.get(1));
      assertEquals(5_000_000_000L, f.get(2));
      assertEquals(1.5, f.get(3));
      assertEquals(1, f.get(4));
      assertEquals(FieldSubType.BOOLEAN, l.type().fields().get(4).subType());
      assertEquals(java.time.LocalDate.of(2024, 2, 29), f.get(5));
      assertEquals("æøå ✓", f.get(6));
      assertEquals("[1,2]", f.get(7));
    }
  }

  @Test
  void defaultEncodingIsLatin1() throws Exception {
    FeatureType t =
        new FeatureType("latin", List.of(FieldDefn.of("s", FieldType.STRING)), List.of());
    try (Dataset ds = Jdal.create("ESRI Shapefile", join(dir, "latin.dbf").toString(), Map.of())) {
      ds.createLayer(t, Map.of()).insert(Feature.builder(t).set(0, "blåbær").build());
    }
    byte[] raw = Vsi.global().readAllBytes(join(dir, "latin.dbf"));
    assertEquals(0x57, raw[29] & 0xFF);
    assertTrue(new String(raw, StandardCharsets.ISO_8859_1).contains("blåbær"));
    try (Dataset ds = Jdal.open(join(dir, "latin.dbf").toString())) {
      assertEquals("blåbær", all(ds.layers().getFirst()).getFirst().get(0));
    }
  }

  @Test
  void writesPrjAndReadsItBack() throws Exception {
    roundTrip("withprj", GeometryType.POINT, Dimension.XY, "POINT (1 2)");
    assertTrue(
        Vsi.global().readString(join(dir, "withprj.prj")).startsWith("GEOGCS[\"GCS_WGS_1984\""));
  }

  @Test
  void directoryOfShapefiles() {
    roundTrip("one", GeometryType.POINT, Dimension.XY, "POINT (1 2)");
    roundTrip("two", GeometryType.POINT, Dimension.XY, "POINT (3 4)");
    try (Dataset ds = Jdal.open(dir)) {
      assertEquals(List.of("one", "two"), ds.layers().stream().map(Layer::name).toList());
      ds.deleteLayer("one");
    }
    assertFalse(Vsi.global().exists(join(dir, "one.shp")));
    assertTrue(Vsi.global().exists(join(dir, "two.shp")));
  }

  @Test
  void corruptFilesAreReportedNotCrashing() throws Exception {
    String shp = join(dir, "bad.shp");
    copy(Autotest.data("ogr/data/shp/gjpoint.shp"), shp);
    copy(Autotest.data("ogr/data/shp/gjpoint.shx"), join(dir, "bad.shx"));
    byte[] b = Vsi.global().readAllBytes(shp);
    Vsi.global()
        .writeBytes(shp, java.util.Arrays.copyOf(b, 110)); // truncated in the middle of the record
    try (Dataset ds = Jdal.open(shp)) {
      assertThrows(JdalException.class, () -> all(ds.layers().getFirst()));
    }
    Vsi.global().writeBytes(join(dir, "garbage.shp"), new byte[200]);
    assertThrows(JdalException.class, () -> Jdal.open(join(dir, "garbage.shp").toString()));
  }

  @Test
  void readingShapefilesFromAZip() throws Exception {
    String zip = "/vsizip/vsimem/" + java.util.UUID.randomUUID() + ".zip";
    try (var main = Vsi.global().open(zip, dev.jdal.vsi.VsiMode.WRITE)) {
      for (String ext : List.of("shp", "shx", "dbf", "prj")) {
        copy(
            Autotest.data("ogr/data/poly." + (ext.equals("prj") ? "PRJ" : ext)),
            zip + "/poly." + ext);
      }
    }
    for (String location :
        List.of(zip, zip + "/poly.shp", "/vsizip/{" + zip.substring(7) + "}/poly.shp")) {
      try (Dataset ds = Jdal.open(location)) {
        Layer l = ds.layers().getFirst();
        assertEquals(10, l.count(), location);
        assertEquals("poly", l.name());
        assertNotNull(l.type().srs());
      }
    }
  }
}
