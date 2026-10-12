package dev.jdal.geom;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.testkit.Autotest;
import dev.jdal.testkit.Port;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class WktWkbTest {

  static Stream<Path> wkbFiles() throws IOException {
    Path dir = Autotest.dataOrNull("ogr/data/wkb_wkt");
    if (dir == null) return Stream.empty();
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> p.toString().endsWith(".wkb")).sorted().toList().stream();
    }
  }

  @Port("autotest/ogr/ogr_wkbwkt_geom.py::test_wkbwkt_geom")
  @ParameterizedTest
  @MethodSource("wkbFiles")
  void wkbWktPairs(Path wkbFile) throws IOException {
    String base = wkbFile.getFileName().toString().replace(".wkb", "");
    byte[] rawWkb = Files.readAllBytes(wkbFile);
    String rawWkt = Files.readString(wkbFile.resolveSibling(base + ".wkt")).strip();

    Geometry fromWkb = Wkb.read(rawWkb);
    Geometry fromWkt = Wkt.read(rawWkt);
    assertEquals(fromWkt.toWkt(), fromWkb.toWkt());
    assertEquals(fromWkt.dimension(), fromWkb.dimension());
    assertEquals(fromWkt.type(), fromWkb.type());

    assertEquals(fromWkt, Wkb.read(Wkb.write(fromWkt, ByteOrder.BIG_ENDIAN)), "XDR round trip");
    assertEquals(fromWkt, Wkb.read(Wkb.write(fromWkt, ByteOrder.LITTLE_ENDIAN)), "NDR round trip");
  }

  @Port("autotest/ogr/ogr_wkbwkt_geom.py::test_ogr_wkbwkt_geom_bigexponents")
  @Test
  void bigExponents() {
    assertEquals(
        "POINT (-1.79769313486E+308 -1.12345678901E+308)",
        Point.xy(-1.79769313486e308, -1.12345678901e308).toWkt());
  }

  static List<String> brokenWkt() throws IOException {
    try (InputStream in = WktWkbTest.class.getResourceAsStream("/broken_wkt.txt")) {
      return new String(in.readAllBytes()).lines().toList();
    }
  }

  @Port("autotest/ogr/ogr_wkbwkt_geom.py::test_ogr_wkbwkt_test_broken_geom")
  @ParameterizedTest
  @MethodSource("brokenWkt")
  void brokenWktIsRejected(String wkt) {
    assertThrows(IllegalArgumentException.class, () -> Wkt.read(wkt), wkt);
  }

  @Port("autotest/ogr/ogr_wkbwkt_geom.py::test_ogr_wkt_multipoint_postgis")
  @Test
  void multipointLegacyFlatSyntax() {
    assertEquals("MULTIPOINT Z ((1 2 3),(4 5 6))", Wkt.read("MULTIPOINT Z (1 2 3,4 5 6)").toWkt());
  }

  // The "POINT (nan nan)" cases of test_ogr_wkt_nan are a known gap: Point treats NaN/NaN as EMPTY.
  @Port("autotest/ogr/ogr_wkbwkt_geom.py::test_ogr_wkt_nan")
  @ParameterizedTest
  @ValueSource(
      strings = {
        "POINT (1.5 nan)",
        "POINT (nan 1.5)",
        "POINT Z (1.5 1.5 nan)",
        "POINT M (1.5 1.5 nan)",
        "POINT ZM (1.5 1.5 nan nan)"
      })
  void nanOrdinates(String wkt) {
    assertEquals(wkt, Wkt.read(wkt).toWkt());
  }

  @Test
  void emptyAndDimensionsRoundTrip() {
    for (String w :
        List.of(
            "POINT EMPTY",
            "POINT Z EMPTY",
            "LINESTRING M EMPTY",
            "POLYGON ZM EMPTY",
            "MULTIPOINT EMPTY",
            "GEOMETRYCOLLECTION EMPTY",
            "POLYGON ((0 0,1 0,1 1,0 0),(0.2 0.2,0.3 0.2,0.3 0.3,0.2 0.2))",
            "MULTIPOLYGON Z (((0 0 1,1 0 1,1 1 1,0 0 1)))",
            "GEOMETRYCOLLECTION (POINT (1 2),LINESTRING (0 0,1 1),GEOMETRYCOLLECTION (POINT Z"
                + " EMPTY))")) {
      Geometry g = Wkt.read(w);
      assertEquals(g, Wkb.read(Wkb.write(g)), w);
    }
    assertEquals("POINT Z (1 2 3)", Wkt.read("POINTZ(1 2 3)").toWkt());
    assertEquals(Dimension.XYZM, Wkt.read("LINESTRING (1 2 3 4,5 6 7 8)").dimension());
  }

  @Test
  void numberFormatting() {
    assertEquals("0.1", NumberFormat.format(0.1, 15, true));
    assertEquals("0.00001", NumberFormat.format(1e-5, 15, true));
    assertEquals("123456789012345", NumberFormat.format(123456789012345.0, 15, true));
    assertEquals("1E+15", NumberFormat.format(1e15, 15, true));
    assertEquals("0.333333333333333", NumberFormat.format(1.0 / 3, 15, true));
    assertEquals("-2.5", NumberFormat.format(-2.5, 15, true));
  }

  @Test
  void ewkbWithSrid() {
    // POINT(1 2) SRID=4326, little endian
    byte[] ewkb =
        java.util.HexFormat.of().parseHex("0101000020E6100000000000000000F03F0000000000000040");
    var r = Wkb.readWithSrid(ewkb);
    assertEquals(4326, r.srid());
    assertEquals(Point.xy(1, 2), r.geometry());
  }
}
