package dev.jdal.driver.csv;

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
import org.junit.jupiter.api.Test;

class CsvWriteTest {

  static String join(String dir, String name) {
    return dir + "/" + name;
  }

  String dir = Autotest.vsimem();

  static FeatureType ambiguous() {
    return new FeatureType(
        "layer",
        List.of(
            FieldDefn.of("foo", FieldType.STRING),
            FieldDefn.of("bar", FieldType.STRING),
            FieldDefn.of("baz", FieldType.STRING),
            FieldDefn.of("intfield", FieldType.INTEGER),
            FieldDefn.of("int64field", FieldType.INTEGER64),
            FieldDefn.of("realfield", FieldType.REAL)),
        List.of());
  }

  String writeAmbiguous(Map<String, String> layerOptions) throws Exception {
    String f = join(dir, "q" + layerOptions.hashCode() + ".csv");
    FeatureType t = ambiguous();
    try (Dataset ds = Jdal.create("CSV", f.toString(), Map.of())) {
      Layer l = ds.createLayer(t, layerOptions);
      l.insert(
          Feature.builder(l.type())
              .set("foo", "00123")
              .set("bar", "x")
              .set("baz", "1.25")
              .set("intfield", 1)
              .set("int64field", 1234567890123L)
              .set("realfield", 1.25)
              .build());
    }
    return Vsi.global().readString(f);
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_string_quoting_if_ambiguous")
  @Test
  void ambiguousStringsAreQuotedByDefault() throws Exception {
    assertTrue(writeAmbiguous(Map.of()).contains("\"00123\",x,\"1.25\",1,1234567890123,1.25"));
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_string_quoting_if_needed")
  @Test
  void quotingOnlyIfNeeded() throws Exception {
    assertTrue(
        writeAmbiguous(Map.of("STRING_QUOTING", "IF_NEEDED"))
            .contains("00123,x,1.25,1,1234567890123,1.25"));
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_string_quoting_always")
  @Test
  void alwaysQuotingAndAppend() throws Exception {
    String f = join(dir, "always.csv");
    FeatureType t =
        new FeatureType(
            "always",
            List.of(
                FieldDefn.of("AREA", FieldType.REAL),
                FieldDefn.of("EAS_ID", FieldType.INTEGER64),
                FieldDefn.of("PRFEDEA", FieldType.STRING)),
            List.of());
    try (Dataset ds = Jdal.create("CSV", f.toString(), Map.of())) {
      Layer l =
          ds.createLayer(
              t, Map.of("CREATE_CSVT", "YES", "STRING_QUOTING", "ALWAYS", "LINEFORMAT", "LF"));
      l.insert(
          Feature.builder(l.type())
              .set("AREA", 215229.266)
              .set("EAS_ID", 168L)
              .set("PRFEDEA", "35043411")
              .build());
    }
    assertEquals(
        "\"AREA\",\"EAS_ID\",\"PRFEDEA\"\n215229.266,168,\"35043411\"\n",
        Vsi.global().readString(f));
    assertEquals(
        "\"Real\",\"Integer64\",\"String\"\n", Vsi.global().readString(join(dir, "always.csvt")));
    try (Dataset ds = Jdal.open(f.toString(), true, Map.of())) {
      Layer l = ds.layers().getFirst();
      l.insert(
          Feature.builder(l.type())
              .set("AREA", 247328.172)
              .set("EAS_ID", 179L)
              .set("PRFEDEA", "35043423")
              .build());
    }
    String data = Vsi.global().readString(f);
    assertTrue(
        data.startsWith(
            "\"AREA\",\"EAS_ID\",\"PRFEDEA\"\n"
                + "215229.266,168,\"35043411\"\n"
                + "247328.172,179,\"35043423\""),
        data);
    try (Dataset ds = Jdal.open(f.toString())) {
      assertEquals(2, ds.layers().getFirst().count());
    }
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_23")
  @Test
  void byteOrderMarkAndWkt() throws Exception {
    FeatureType t =
        new FeatureType(
            "utf8",
            List.of(FieldDefn.of("foo", FieldType.INTEGER), FieldDefn.of("bar", FieldType.STRING)),
            List.of(new GeomFieldDefn("", GeometryType.POINT, Dimension.XY, null, true)));
    String f = join(dir, "utf8.csv");
    try (Dataset ds = Jdal.create("CSV", f.toString(), Map.of())) {
      Layer l = ds.createLayer(t, Map.of("WRITE_BOM", "YES", "GEOMETRY", "AS_WKT"));
      l.insert(
          Feature.builder(l.type())
              .set("foo", 123)
              .set("bar", "baz")
              .geometry(Point.xy(0, 1))
              .build());
    }
    byte[] data = Vsi.global().readAllBytes(f);
    assertEquals("ï»¿WKT", new String(data, 0, 6, StandardCharsets.ISO_8859_1));
    try (Dataset ds = Jdal.open(f.toString())) {
      Layer l = ds.layers().getFirst();
      assertEquals("WKT", l.type().fields().getFirst().name());
      assertEquals(Point.xy(0, 1), l.features().findFirst().orElseThrow().geometry());
    }
    Vsi.global().delete(f);
    try (Dataset ds = Jdal.create("CSV", f.toString(), Map.of())) {
      ds.createLayer(t, Map.of("GEOMETRY", "AS_WKT"));
    }
    assertEquals("WKT,foo,bar\n", Vsi.global().readString(f));
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_24")
  @Test
  void singleColumnWithEmptyValue() throws Exception {
    FeatureType t =
        new FeatureType("single", List.of(FieldDefn.of("foo", FieldType.STRING)), List.of());
    String f = join(dir, "single.csv");
    try (Dataset ds = Jdal.create("CSV", f.toString(), Map.of())) {
      Layer l = ds.createLayer(t, Map.of());
      l.insert(Feature.builder(l.type()).build());
      l.insert(Feature.builder(l.type()).set(0, "bar").build());
    }
    try (Dataset ds = Jdal.open(f.toString())) {
      Layer l = ds.layers().getFirst();
      assertEquals(1, l.type().fields().size());
      List<Feature> fs = l.features().toList();
      assertEquals(List.of("", "bar"), fs.stream().map(x -> x.get(0)).toList());
    }
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_33")
  @Test
  void subtypesSurviveThroughCsvt() throws Exception {
    FeatureType t =
        new FeatureType(
            "test",
            List.of(
                FieldDefn.of("b", FieldType.INTEGER, FieldSubType.BOOLEAN),
                FieldDefn.of("int16", FieldType.INTEGER, FieldSubType.INT16),
                FieldDefn.of("float32", FieldType.REAL, FieldSubType.FLOAT32),
                FieldDefn.of("when", FieldType.DATETIME),
                FieldDefn.of("list", FieldType.STRING_LIST),
                FieldDefn.of("w", FieldType.REAL).withWidth(10, 3)),
            List.of());
    String f = join(dir, "subtypes.csv");
    try (Dataset ds = Jdal.create("CSV", f.toString(), Map.of())) {
      Layer l = ds.createLayer(t, Map.of("CREATE_CSVT", "YES"));
      l.insert(
          Feature.builder(l.type())
              .set(0, 1)
              .set(1, -32768)
              .set(2, 1.23)
              .set(3, "2024-02-29T12:30:45Z")
              .set(4, List.of("a", "b,c"))
              .set(5, 2.5)
              .build());
    }
    try (Dataset ds = Jdal.open(f.toString())) {
      Layer l = ds.layers().getFirst();
      assertEquals(FieldSubType.BOOLEAN, l.type().fields().get(0).subType());
      assertEquals(FieldSubType.INT16, l.type().fields().get(1).subType());
      assertEquals(FieldSubType.FLOAT32, l.type().fields().get(2).subType());
      assertEquals(FieldType.DATETIME, l.type().fields().get(3).type());
      assertEquals(FieldType.STRING_LIST, l.type().fields().get(4).type());
      assertEquals(10, l.type().fields().get(5).width());
      assertEquals(3, l.type().fields().get(5).precision());
      Feature x = l.features().findFirst().orElseThrow();
      assertEquals(1, x.get(0));
      assertEquals(-32768, x.get(1));
      assertEquals(1.23, (Double) x.get(2), 1e-6);
      assertEquals(java.time.OffsetDateTime.parse("2024-02-29T12:30:45Z"), x.get(3));
      assertEquals(List.of("a", "b,c"), x.get(4));
      assertEquals(2.5, x.get(5));
    }
    assertTrue(
        Vsi.global()
            .readString(join(dir, "subtypes.csvt"))
            .startsWith("\"Integer(Boolean)\",\"Integer(Int16)\",\"Real(Float32)\""));
  }

  @Test
  void coordinateGeometryOptions() throws Exception {
    FeatureType t =
        new FeatureType(
            "pts",
            List.of(FieldDefn.of("name", FieldType.STRING)),
            List.of(
                new GeomFieldDefn(
                    "", GeometryType.POINT, Dimension.XYZ, SpatialReference.WGS84, true)));
    Map<String, String> modes =
        Map.of(
            "AS_XY",
            "X,Y,name\n1.5,2.5,a\n",
            "AS_YX",
            "Y,X,name\n2.5,1.5,a\n",
            "AS_XYZ",
            "X,Y,Z,name\n1.5,2.5,3,a\n");
    for (var e : modes.entrySet()) {
      String f = join(dir, e.getKey() + ".csv");
      try (Dataset ds = Jdal.create("CSV", f.toString(), Map.of())) {
        Layer l = ds.createLayer(t, Map.of("GEOMETRY", e.getKey()));
        l.insert(Feature.builder(l.type()).set(0, "a").geometry(Point.xyz(1.5, 2.5, 3)).build());
      }
      assertEquals(e.getValue(), Vsi.global().readString(f), e.getKey());
    }
  }

  @Test
  void ignoredGeometryIsReported() throws Exception {
    FeatureType t =
        new FeatureType(
            "g",
            List.of(),
            List.of(new GeomFieldDefn("", GeometryType.POINT, Dimension.XY, null, true)));
    List<String> warnings =
        Diagnostics.capture(
            () -> {
              try (Dataset ds = Jdal.create("CSV", join(dir, "g.csv").toString(), Map.of())) {
                ds.createLayer(t, Map.of());
              }
            });
    assertEquals(1, warnings.size());
    assertTrue(warnings.getFirst().contains("GEOMETRY"));
  }

  @Test
  void directoryDatasetWritesOneFilePerLayer() throws Exception {
    String d = join(dir, "out");
    try (Dataset ds = Jdal.create("CSV", d.toString(), Map.of())) {
      ds.createLayer(
              new FeatureType("one", List.of(FieldDefn.of("a", FieldType.INTEGER)), List.of()),
              Map.of())
          .insert(
              Feature.builder(
                      new FeatureType(
                          "one", List.of(FieldDefn.of("a", FieldType.INTEGER)), List.of()))
                  .set(0, 1)
                  .build());
      ds.createLayer(
          new FeatureType("two", List.of(FieldDefn.of("b", FieldType.INTEGER)), List.of()),
          Map.of());
    }
    assertTrue(Vsi.global().exists(join(d, "one.csv")));
    assertEquals("b\n", Vsi.global().readString(join(d, "two.csv")));
    try (Dataset ds = Jdal.open(d.toString())) {
      assertEquals(2, ds.layers().size());
    }
  }
}
