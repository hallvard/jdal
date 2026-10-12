package dev.jdal.driver.csv;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.*;
import dev.jdal.feature.*;
import dev.jdal.geom.*;
import dev.jdal.testkit.Autotest;
import dev.jdal.testkit.Port;
import dev.jdal.vsi.Vsi;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

class CsvReadTest {

  static String join(String dir, String name) {
    return dir + "/" + name;
  }

  static Dataset open(String autotestRelative, String... options) {
    Map<String, String> o = new java.util.HashMap<>();
    for (String s : options) {
      int eq = s.indexOf('=');
      o.put(s.substring(0, eq), s.substring(eq + 1));
    }
    return Jdal.open(Autotest.data(autotestRelative).toString(), false, o);
  }

  static Dataset openText(String dir, String name, String text, String... options)
      throws Exception {
    String f = join(dir, name);
    Vsi.global().writeString(f, text);
    Map<String, String> o = new java.util.HashMap<>();
    for (String s : options) {
      int eq = s.indexOf('=');
      o.put(s.substring(0, eq), s.substring(eq + 1));
    }
    return Jdal.open(f.toString(), false, o);
  }

  static List<Feature> all(Layer l) {
    try (Stream<Feature> s = l.features()) {
      return s.toList();
    }
  }

  static String text(Feature f, int i) {
    return FieldValues.format(f.type().fields().get(i), f.get(i));
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_2")
  @Test
  void primeMeridian() {
    try (Dataset ds = open("ogr/data/prime_meridian.csv")) {
      Layer l = ds.requireLayer("prime_meridian");
      assertThrows(JdalException.class, () -> ds.createLayer(l.type().withName("foo"), Map.of()));
      assertThrows(JdalException.class, () -> l.insert(Feature.builder(l.type()).build()));
      List<Feature> fs = all(l);
      assertEquals(
          List.of("8901", "8902", "8903", "8904"),
          fs.stream().map(f -> f.getString("PRIME_MERIDIAN_CODE")).toList());
      assertEquals(
          List.of(
              "",
              "Instituto Geografico e Cadastral; Lisbon",
              "Institut Geographique National (IGN), Paris",
              "Instituto Geografico \"Augustin Cadazzi\" (IGAC); Bogota"),
          fs.stream().map(f -> f.getString("INFORMATION_SOURCE")).toList());
    }
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_11")
  @Test
  void csvtTypesWidthsAndValues() {
    try (Dataset ds = open("ogr/data/csv/testcsvt.csv")) {
      Layer l = ds.requireLayer("testcsvt");
      FeatureType t = l.type();
      assertEquals(5, t.fields().get(0).width());
      assertEquals(10, t.fields().get(1).width());
      assertEquals(7, t.fields().get(1).precision());
      assertEquals(15, t.fields().get(2).width());
      assertEquals(FieldType.DATETIME, t.fields().get(6).type());
      assertEquals(FieldType.DATE, t.fields().get(7).type());
      assertEquals(FieldType.TIME, t.fields().get(8).type());
      List<Feature> fs = all(l);
      assertEquals(2, fs.size());
      assertEquals(12, fs.getFirst().get("INTCOL"));
      assertEquals(5.7, fs.getFirst().get("REALCOL"));
      assertEquals("foo", fs.getFirst().get("STRINGCOL"));
      assertEquals("2008/12/25 11:22:33", text(fs.getFirst(), 6));
      assertEquals("2008/12/25", text(fs.getFirst(), 7));
      assertEquals("11:22:33", text(fs.getFirst(), 8));
      assertEquals("", text(fs.get(1), 6));
      assertNull(fs.get(1).get("INTCOL"));
    }
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_17")
  @Test
  void wktColumnIsGeometryAndField() {
    try (Dataset ds = open("ogr/data/wkt.csv")) {
      Layer l = ds.layers().getFirst();
      assertEquals(GeometryType.UNKNOWN, l.type().geometryType());
      List<Feature> fs = all(l);
      assertEquals(
          "POLYGON((6.25 1.25,7.25 1.25,7.25 2.25,6.25 2.25,6.25 1.25))", fs.getFirst().get("WKT"));
      assertEquals(
          Wkt.read("POLYGON((6.25 1.25,7.25 1.25,7.25 2.25,6.25 2.25,6.25 1.25))"),
          fs.getFirst().geometry());
      assertEquals(
          Wkt.read("POLYGON((1.001 1.001,3.999 3.999,3.2 1.6,1.001 1.001))"), fs.get(2).geometry());
    }
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_19")
  @Test
  void emptyValuesOfNonStringColumnsAreNull() {
    try (Dataset ds = open("ogr/data/csv/testnull.csv")) {
      Feature f = all(ds.requireLayer("testnull")).getFirst();
      assertEquals(12, f.get("INTCOL"));
      assertEquals(5.7, f.get("REALCOL"));
      assertNull(f.get("INTCOL2")); // "foo" is not an integer
      assertNull(f.get("REALCOL2"));
      assertEquals("foo", f.get("STRINGCOL"));
    }
  }

  @Port({"autotest/ogr/ogr_csv.py::test_ogr_csv_20", "autotest/ogr/ogr_csv.py::test_ogr_csv_21"})
  @Test
  void numericHeaders() {
    record Case(String file, List<String> names) {}
    for (Case c :
        List.of(
            new Case("testnumheader1", List.of("1 - 2", "2-3")),
            new Case("testnumheader2", List.of("field_1", "field_2")),
            new Case("testquoteheader1", List.of("test", "2000", "2000.12")),
            new Case("testquoteheader2", List.of("field_1", "field_2", "field_3")))) {
      try (Dataset ds = open("ogr/data/csv/" + c.file() + ".csv")) {
        assertEquals(
            c.names(),
            ds.requireLayer(c.file()).type().fields().stream().map(FieldDefn::name).toList(),
            c.file());
      }
    }
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_22")
  @Test
  void utf8Bom() {
    try (Dataset ds = open("ogr/data/csv/csv_with_utf8_bom.csv")) {
      assertEquals("id", ds.layers().getFirst().type().fields().getFirst().name());
    }
  }

  @Port({
    "autotest/ogr/ogr_csv.py::test_ogr_csv_double_quotes_in_middle_of_field",
    "autotest/ogr/ogr_csv.py::test_ogr_csv_double_quotes_in_middle_of_field_bis"
  })
  @Test
  void doubleQuotesInTheMiddleOfAField() {
    try (Dataset ds = open("ogr/data/csv/double_quotes_in_middle_of_field.csv")) {
      Feature f = all(ds.layers().getFirst()).getFirst();
      assertEquals("1", f.get("id"));
      assertEquals("50°46'06.6\"N 116°42'04.4", f.get("coord"));
      assertEquals("foo", f.get("str"));
    }
    try (Dataset ds = open("ogr/data/csv/double_quotes_in_middle_of_field_bis.csv")) {
      List<Feature> fs = all(ds.layers().getFirst());
      assertEquals("two\"with quote", fs.get(0).get("second"));
      assertEquals("30", fs.get(1).get("third"));
      assertEquals("twenty\"with quote", fs.get(1).get("second"));
    }
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_single_column")
  @Test
  void singleColumnWithWkt() {
    try (Dataset ds = open("ogr/data/csv/single_column.csv")) {
      Feature f = all(ds.layers().getFirst()).getFirst();
      assertEquals("POINT (1 2)", f.get("WKT"));
      assertEquals("POINT (1 2)", f.geometry().toWkt());
    }
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_read_header_with_line_break")
  @Test
  void headerWithLineBreak() {
    try (Dataset ds = open("ogr/data/csv/header_with_line_break.csv")) {
      Layer l = ds.layers().getFirst();
      assertEquals(
          List.of(
              "Column one",
              "Column two",
              "Column with a\nline break",
              "Column three",
              "Another\nline break",
              "Column four",
              "Column five"),
          l.type().fields().stream().map(FieldDefn::name).toList());
      assertEquals(
          List.of("1", "2", "3", "4", "5", "6", "7"),
          all(l).getFirst().values().stream().map(Object::toString).toList());
    }
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_inf_nan")
  @Test
  void infAndNan() {
    try (Dataset ds = open("ogr/data/csv/inf_nan.csv", "AUTODETECT_TYPE=YES")) {
      Layer l = ds.layers().getFirst();
      assertEquals(FieldType.REAL, l.type().fields().get(1).type());
      List<Feature> fs = all(l);
      assertEquals(10.0, fs.get(0).get("v"));
      assertEquals(Double.POSITIVE_INFINITY, fs.get(1).get("v"));
      assertEquals(Double.NEGATIVE_INFINITY, fs.get(2).get("v"));
      assertTrue(Double.isNaN((Double) fs.get(3).get("v")));
    }
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_unbalanced_double_quotes")
  @Test
  void unbalancedDoubleQuotes() {
    try (Dataset ds = open("ogr/data/csv/unbalanced_double_quotes.csv")) {
      JdalException e = assertThrows(JdalException.class, () -> all(ds.layers().getFirst()));
      assertTrue(
          e.getMessage()
              .contains(
                  "CSV file has unbalanced number of double-quotes. Corrupted data will likely be"
                      + " returned"));
    }
  }

  String dir = Autotest.vsimem();

  static Stream<String> separators() {
    return Stream.of(",", ";", "\t", "|");
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> separatorPairs() {
    return Stream.of(
        org.junit.jupiter.params.provider.Arguments.of(",", ";"),
        org.junit.jupiter.params.provider.Arguments.of(";", ","),
        org.junit.jupiter.params.provider.Arguments.of("\t", ","),
        org.junit.jupiter.params.provider.Arguments.of("|", ","));
  }

  @Port({
    "autotest/ogr/ogr_csv.py::test_ogr_csv_separator_single_occurence_no_space",
    "autotest/ogr/ogr_csv.py::test_ogr_csv_separator_single_occurence_space",
    "autotest/ogr/ogr_csv.py::test_ogr_csv_pipe_separated"
  })
  @ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("separators")
  void singleSeparatorOccurrenceIsDetected(String sep) throws Exception {
    for (String header :
        List.of("foo" + sep + "bar" + sep + "baz", "foo " + sep + " bar " + sep + " baz")) {
      String firstName = header.startsWith("foo ") ? "foo " : "foo";
      List<String> warnings =
          Diagnostics.capture(
              () -> {
                try (Dataset ds =
                    openText(dir, "test.csv", header + "\n1" + sep + "2" + sep + "3\n")) {
                  assertEquals("1", all(ds.layers().getFirst()).getFirst().getString(firstName));
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      assertEquals(List.of(), warnings);
    }
  }

  @Port({
    "autotest/ogr/ogr_csv.py::test_ogr_csv_separator_with_other_sep",
    "autotest/ogr/ogr_csv.py::test_ogr_csv_separator_with_other_sep_in_string"
  })
  @ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("separatorPairs")
  void otherSeparatorCandidatesWarn(String sep, String otherSep) {
    List<String> quiet =
        Diagnostics.capture(
            () -> {
              try (Dataset ds =
                  openText(
                      dir,
                      "q.csv",
                      "foo" + sep + "\"bar" + otherSep + otherSep + "rr\"" + sep + "baz\n1" + sep
                          + "2" + sep + "3\n")) {
                assertEquals(
                    "2",
                    all(ds.layers().getFirst()).getFirst().get("bar" + otherSep + otherSep + "rr"));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    assertEquals(List.of(), quiet);
    List<String> warned =
        Diagnostics.capture(
            () -> {
              try (Dataset ds =
                  openText(
                      dir,
                      "w.csv",
                      "foo" + sep + "bar" + otherSep + "rr" + sep + "baz\n1" + sep + "2" + sep
                          + "3\n")) {
                assertEquals(
                    "2", all(ds.layers().getFirst()).getFirst().get("bar" + otherSep + "rr"));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    assertEquals(1, warned.size());
    assertTrue(warned.getFirst().contains("other candidate separator"), warned.getFirst());
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_separator_open_option")
  @ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("separatorOptions")
  void separatorOpenOption(String opt, String other) throws Exception {
    char sep = CsvSchema.separatorFor(opt);
    List<String> warnings =
        Diagnostics.capture(
            () -> {
              try (Dataset ds =
                  openText(
                      dir,
                      "t.csv",
                      "foo" + sep + "bar" + other + other + other + other + "rr" + sep + "baz\n1"
                          + sep + "2" + sep + "3\n",
                      "SEPARATOR=" + opt)) {
                assertEquals(
                    "2",
                    all(ds.layers().getFirst())
                        .getFirst()
                        .get("bar" + other + other + other + other + "rr"));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    assertEquals(List.of(), warnings);
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> separatorOptions() {
    return Stream.of(
        org.junit.jupiter.params.provider.Arguments.of("COMMA", ";"),
        org.junit.jupiter.params.provider.Arguments.of("SEMICOLON", ","),
        org.junit.jupiter.params.provider.Arguments.of("TAB", ","),
        org.junit.jupiter.params.provider.Arguments.of("PIPE", ","),
        org.junit.jupiter.params.provider.Arguments.of("SPACE", ","));
  }

  @Test
  void xyColumnsBuildPoints() throws Exception {
    String csv = "Latitude,Longitude,Name\n48.1,0.25,\"First point\"\n49.2,1.1,\"Second point\"\n";
    try (Dataset ds =
        openText(
            dir,
            "test.csv",
            csv,
            "X_POSSIBLE_NAMES=Lon*",
            "Y_POSSIBLE_NAMES=Lat*",
            "KEEP_GEOM_COLUMNS=NO")) {
      Layer l = ds.layers().getFirst();
      assertEquals(GeometryType.POINT, l.type().geometryType());
      assertEquals(List.of("Name"), l.type().fields().stream().map(FieldDefn::name).toList());
      assertEquals(Point.xy(0.25, 48.1), all(l).getFirst().geometry());
    }
    try (Dataset ds =
        openText(
            dir, "test2.csv", csv, "X_POSSIBLE_NAMES=Longitude", "Y_POSSIBLE_NAMES=Latitude")) {
      assertEquals(3, ds.layers().getFirst().type().fields().size());
    }
  }

  @Test
  void directoryOfCsvFilesIsADataset() throws Exception {
    Vsi.global().writeString(join(dir, "a.csv"), "x,y\n1,2\n");
    Vsi.global().writeString(join(dir, "b.csv"), "p,q\n3,4\n");
    try (Dataset ds = Jdal.open(dir.toString())) {
      assertEquals(List.of("a", "b"), ds.layers().stream().map(Layer::name).toList());
    }
  }

  @Test
  void readingAGzippedCsv() throws Exception {
    String gz = dir + "/data.csv.gz";
    try (var w = Vsi.global().newWriter("/vsigzip/" + gz)) {
      w.write("id,name\n1,a\n2,b\n");
    }
    try (Dataset ds = Jdal.open("CSV:/vsigzip/" + gz)) {
      Layer l = ds.layers().getFirst();
      assertEquals(2, l.count());
      assertEquals("b", l.feature(2).orElseThrow().get("name"));
    }
  }
}
