package dev.jdal.srs;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.geom.Point;
import org.junit.jupiter.api.Test;

class SpatialReferenceTest {

  @Test
  void parsesUserInput() {
    assertEquals(4326, SpatialReference.fromUserInput("EPSG:4326").epsg());
    assertEquals(25832, SpatialReference.fromUserInput("urn:ogc:def:crs:EPSG::25832").epsg());
    assertEquals(
        32633,
        SpatialReference.fromUserInput(
                "http://www.opengis.net/def/crs/EPSG/0/32633".replace("http", "https"))
            .epsg());
    assertEquals(
        "+proj=longlat +datum=WGS84",
        SpatialReference.fromUserInput("+proj=longlat +datum=WGS84").proj4());
    var wkt =
        SpatialReference.fromUserInput(
            "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]],"
                + "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433],AUTHORITY[\"EPSG\",\"4326\"]]");
    assertEquals(4326, wkt.epsg());
    assertThrows(IllegalArgumentException.class, () -> SpatialReference.fromUserInput("nonsense"));
  }

  @Test
  void transformsWgs84ToUtm() {
    var t =
        CoordinateTransformation.between(SpatialReference.WGS84, SpatialReference.fromEpsg(32633));
    Point p = (Point) t.transform(Point.xy(15, 0)); // central meridian of zone 33
    assertEquals(500000, p.x(), 1e-3);
    assertEquals(0, p.y(), 1e-3);
    var back =
        CoordinateTransformation.between(SpatialReference.fromEpsg(32633), SpatialReference.WGS84);
    Point q = (Point) back.transform(Point.xy(500000, 6000000));
    assertEquals(15, q.x(), 1e-9);
    assertEquals(54.2, q.y(), 0.1);
  }

  @Test
  void identityTransformKeepsGeometry() {
    var p = Point.xy(1, 2);
    assertEquals(
        p,
        CoordinateTransformation.between(SpatialReference.WGS84, SpatialReference.fromEpsg(4326))
            .transform(p));
  }

  @Test
  void identifiesWktByNameAndGeneratesWkt() {
    // the .prj of GDAL's poly.shp has no authority
    String bng =
        "PROJCS[\"OSGB 1936 / British National Grid\",GEOGCS[\"OSGB"
            + " 1936\",DATUM[\"D_OSGB_1936\"]]]";
    assertEquals(27700, SpatialReference.fromWkt(bng).epsg());
    assertEquals(
        4326, SpatialReference.fromWkt("GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\"]]").epsg());
    assertEquals(
        32633, SpatialReference.fromWkt("PROJCS[\"WGS_1984_UTM_Zone_33N\",GEOGCS[\"x\"]]").epsg());
    assertEquals(
        26910, SpatialReference.fromWkt("PROJCS[\"NAD83 / UTM zone 10N\",GEOGCS[\"x\"]]").epsg());
    assertEquals(0, SpatialReference.fromWkt("PROJCS[\"Something Else\",GEOGCS[\"x\"]]").epsg());
    String esri = SpatialReference.fromEpsg(4326).toEsriWkt().orElseThrow();
    assertEquals(
        "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],"
            + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]",
        esri);
    assertEquals(4326, SpatialReference.fromWkt(esri).epsg());
    for (int code : new int[] {4326, 4269, 4258, 32633, 32733, 26910, 25832, 27700, 3857}) {
      assertEquals(
          code,
          SpatialReference.fromWkt(SpatialReference.fromEpsg(code).toWkt().orElseThrow()).epsg(),
          "wkt " + code);
      assertEquals(
          code,
          SpatialReference.fromWkt(SpatialReference.fromEpsg(code).toEsriWkt().orElseThrow())
              .epsg(),
          "esri " + code);
    }
    assertTrue(
        SpatialReference.fromEpsg(32633).toWkt().orElseThrow().contains("\"central_meridian\",15"));
    assertTrue(SpatialReference.fromEpsg(2193).toWkt().isEmpty());
  }
}
