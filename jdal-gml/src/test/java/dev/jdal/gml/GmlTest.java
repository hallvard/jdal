package dev.jdal.gml;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.Dataset;
import dev.jdal.core.Jdal;
import dev.jdal.core.JdalException;
import dev.jdal.core.Layer;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FieldType;
import dev.jdal.geom.Geometry;
import dev.jdal.geom.GeometryType;
import dev.jdal.geom.Wkt;
import dev.jdal.testkit.Autotest;
import dev.jdal.testkit.Port;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ports of autotest/ogr/ogr_gml.py that read GDAL's sample files in place. */
class GmlTest {

  static Dataset open(String name) {
    return Jdal.open(Autotest.data("ogr/data/gml/" + name).toString());
  }

  static void assertGeometry(String wkt, Geometry actual) {
    assertEquals(Wkt.read(wkt).toWkt(), actual.toWkt());
  }

  @Port("autotest/ogr/ogr_gml.py::test_ogr_gml_1")
  @Test
  void ionicWfsFile() {
    try (Dataset ds = open("ionic_wfs.gml")) {
      assertEquals("GML", ds.driver().name());
      assertEquals(1, ds.layers().size());
      Layer l = ds.requireLayer("GEM");
      List<Feature> fs = l.features().toList();
      assertEquals(1, fs.size());
      assertEquals("Aartselaar", fs.getFirst().get("Name"));
      assertGeometry(
          "POLYGON ((44038 511549,44015 511548,43994 511522,43941 511539,43844 511514,43754"
              + " 511479,43685 511521,43594 511505,43619 511452,43645 511417,4363 511387,437"
              + " 511346,43749 511298,43808 511229,43819 511205,4379 511185,43728 511167,43617"
              + " 511175,43604 511151,43655 511125,43746 511143,43886 511154,43885 511178,43928"
              + " 511186,43977 511217,4404 511223,44008 511229,44099 51131,44095 511335,44106"
              + " 51135,44127 511379,44124 511435,44137 511455,44105 511467,44098 511484,44086"
              + " 511499,4407 511506,44067 511535,44038 511549))",
          fs.getFirst().geometry());
    }
  }

  @Port("autotest/ogr/ogr_gml.py::test_ogr_gml_3")
  @Test
  void roadSegments() {
    try (Dataset ds = open("rnf_eg.gml")) {
      assertEquals(1, ds.layers().size());
      Layer l = ds.requireLayer("RoadSegment");
      Feature f = l.features().findFirst().orElseThrow();
      assertEquals(817792, f.get("ngd_id"));
      assertEquals("HWY", f.get("type"));
      assertGeometry(
          "LINESTRING (-63.500411040289066 46.240122507771368,-63.501009714909742"
              + " 46.240344881690326,-63.502170462373471 46.241041855639622,-63.505862621395394"
              + " 46.24195250605576,-63.506719184531178 46.242002742901576,-63.507197272602212"
              + " 46.241931577811606,-63.508403092799554 46.241752283460158,-63.509946573455622"
              + " 46.241745397977233)",
          f.geometry());
      assertEquals(1, l.count());
    }
  }

  @Port("autotest/ogr/ogr_gml.py::test_ogr_gml_7")
  @Test
  void typesFromTheSchemaFile() {
    System.setProperty("GML_EXPOSE_FID", "NO");
    try (Dataset ds = open("test_point.gml")) {
      Layer l = ds.layers().getFirst();
      assertEquals(5, l.count());
      assertEquals(FieldType.REAL, l.type().fields().get(0).type());
      assertEquals(FieldType.INTEGER, l.type().fields().get(1).type());
      assertEquals(FieldType.STRING, l.type().fields().get(2).type());
    } finally {
      System.clearProperty("GML_EXPOSE_FID");
    }
  }

  @Test
  void featureIdsAreExposed() {
    try (Dataset ds = open("test_point.gml")) {
      Layer l = ds.layers().getFirst();
      assertEquals("fid", l.type().fields().getFirst().name());
      assertEquals("F0", l.features().findFirst().orElseThrow().get("fid"));
    }
    try (Dataset ds = Jdal.open(Autotest.data("ogr/data/gml/archsites.gml").toString())) {
      assertEquals("gml_id", ds.layers().getFirst().type().fields().getFirst().name());
    }
  }

  @Port("autotest/ogr/ogr_gml.py::test_ogr_gml_68")
  @Test
  void multipolygonChoice() {
    try (Dataset ds = open("choicepolygonmultipolygon.gml")) {
      Layer l = ds.layers().getFirst();
      assertEquals(GeometryType.MULTIPOLYGON, l.type().geometryType());
      List<Feature> fs = l.features().toList();
      assertEquals("MULTIPOLYGON (((0 0,0 1,1 1,1 0,0 0)))", fs.get(0).geometry().toWkt());
      assertEquals(
          "MULTIPOLYGON (((0 0,0 1,1 1,1 0,0 0)),((10 0,10 1,11 1,11 0,10 0)))",
          fs.get(1).geometry().toWkt());
    }
  }

  @Port("autotest/ogr/ogr_gml.py::test_ogr_gml_first_feature_without_geometry")
  @Test
  void firstFeatureWithoutGeometry() {
    try (Dataset ds = open("first_feature_without_geometry.gml")) {
      assertEquals(GeometryType.POINT, ds.layers().getFirst().type().geometryType());
    }
  }

  @Port("autotest/ogr/ogr_gml.py::test_ogr_gml_read_srsDimension_3_on_top_gml_Envelope")
  @Test
  void dimensionFromTheTopEnvelope() {
    try (Dataset ds = open("global_srsDimension_3.gml")) {
      Feature f = ds.layers().getFirst().features().findFirst().orElseThrow();
      assertEquals("LINESTRING Z (1 2 3,4 5 6,7 8 9,10 11 12)", f.geometry().toWkt());
    }
  }

  @Port("autotest/ogr/ogr_gml.py::test_ogr_gml_read_feature_with_gml_description")
  @Test
  void descriptionIdentifierAndName() {
    try (Dataset ds = open("feature_with_gml_description.gml")) {
      Layer l = ds.layers().getFirst();
      assertEquals(
          List.of("gml_id", "description", "identifier", "name", "bar", "not_found_in_gml"),
          l.type().fields().stream().map(x -> x.name()).toList());
      Feature f = l.features().findFirst().orElseThrow();
      assertEquals("foo.0", f.get("gml_id"));
      assertEquals("gml_description", f.get("description"));
      assertEquals("gml_identifier", f.get("identifier"));
      assertEquals("gml_name", f.get("name"));
      assertEquals(1, f.get("bar"));
    }
  }

  @Port("autotest/ogr/ogr_gml.py::test_ogr_gml_billion_laugh")
  @Test
  void entityExpansionAttacksAreRejected() {
    assertThrows(
        JdalException.class,
        () -> {
          try (Dataset ds = open("billionlaugh.gml")) {
            for (Layer l : ds.layers()) l.features().forEach(f -> {});
          }
        });
  }

  @Port("autotest/ogr/ogr_gml.py::test_ogr_gml_64")
  @Test
  void parsesRnfFile() {
    try (Dataset ds = open("rnf_eg.gml")) {
      assertTrue(ds.layers().getFirst().features().findAny().isPresent());
    }
  }

  @Test
  void axisOrderOfGeographicCrsNames() {
    assertTrue(SrsNames.parse("urn:ogc:def:crs:EPSG::4326").swapsAxes());
    assertTrue(SrsNames.parse("http://www.opengis.net/def/crs/EPSG/0/4258").swapsAxes());
    assertFalse(SrsNames.parse("EPSG:4326").swapsAxes());
    assertFalse(SrsNames.parse("urn:ogc:def:crs:OGC:1.3:CRS84").swapsAxes());
    assertFalse(SrsNames.parse("urn:ogc:def:crs:EPSG::25833").swapsAxes());
    assertEquals(25833, SrsNames.parse("urn:ogc:def:crs:EPSG::25833").epsg());
    assertTrue(SrsNames.parse("urn:ogc:def:crs:EPSG::3035").swapsAxes());
  }

  @Test
  void geometriesInUrnCrsAreSwappedToXy() {
    String xml =
        """
        <wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0" xmlns:gml="http://www.opengis.net/gml/3.2" xmlns:t="urn:t">
          <wfs:member><t:place gml:id="p.1">
            <t:name>a</t:name>
            <t:geom><gml:Point srsName="urn:ogc:def:crs:EPSG::4326"><gml:pos>59.9 10.7</gml:pos></gml:Point></t:geom>
          </t:place></wfs:member>
          <wfs:member><t:place gml:id="p.2">
            <t:name>b</t:name>
            <t:geom><gml:Point srsName="EPSG:4326"><gml:pos>10.7 59.9</gml:pos></gml:Point></t:geom>
          </t:place></wfs:member>
        </wfs:FeatureCollection>
        """;
    GmlSchema.Inference inf = new GmlSchema.Inference(true, true);
    List<GmlFeature> raw = GmlFeatureReader.readAll(xml);
    raw.forEach(inf::add);
    var type = inf.build().get("place");
    assertEquals("gml_id", type.fields().getFirst().name());
    Feature a = GmlSchema.convert(type, raw.get(0), 0);
    Feature b = GmlSchema.convert(type, raw.get(1), 1);
    assertEquals("POINT (10.7 59.9)", a.geometry().toWkt());
    assertEquals("POINT (10.7 59.9)", b.geometry().toWkt());
    assertEquals("p.1", a.get("gml_id"));
  }

  @Test
  void geometryKinds() {
    String xml =
        """
        <c xmlns:gml="http://www.opengis.net/gml/3.2" xmlns:t="urn:t"><gml:featureMember><t:f>
          <t:a><gml:Curve><gml:segments><gml:LineStringSegment><gml:posList>0 0 1 1</gml:posList></gml:LineStringSegment>
            <gml:LineStringSegment><gml:posList>1 1 2 0</gml:posList></gml:LineStringSegment></gml:segments></gml:Curve></t:a>
          <t:b><gml:Surface><gml:patches><gml:PolygonPatch><gml:exterior><gml:LinearRing><gml:posList>0 0 4 0 4 4 0 4 0 0</gml:posList></gml:LinearRing></gml:exterior>
            <gml:interior><gml:LinearRing><gml:posList>1 1 2 1 2 2 1 2 1 1</gml:posList></gml:LinearRing></gml:interior></gml:PolygonPatch></gml:patches></gml:Surface></t:b>
          <t:c><gml:MultiPoint><gml:pointMember><gml:Point><gml:pos>1 2</gml:pos></gml:Point></gml:pointMember><gml:pointMember><gml:Point><gml:pos>3 4</gml:pos></gml:Point></gml:pointMember></gml:MultiPoint></t:c>
          <t:d><gml:Point><gml:coordinates>5,6,7</gml:coordinates></gml:Point></t:d>
          <t:e><gml:Envelope><gml:lowerCorner>0 0</gml:lowerCorner><gml:upperCorner>2 1</gml:upperCorner></gml:Envelope></t:e>
        </t:f></gml:featureMember></c>
        """;
    GmlFeature f = GmlFeatureReader.readAll(xml).getFirst();
    assertEquals("LINESTRING (0 0,1 1,2 0)", f.geometries().get(0).geometry().toWkt());
    assertEquals(
        "POLYGON ((0 0,4 0,4 4,0 4,0 0),(1 1,2 1,2 2,1 2,1 1))",
        f.geometries().get(1).geometry().toWkt());
    assertEquals("MULTIPOINT ((1 2),(3 4))", f.geometries().get(2).geometry().toWkt());
    assertEquals("POINT Z (5 6 7)", f.geometries().get(3).geometry().toWkt());
    assertEquals("POLYGON ((0 0,2 0,2 1,0 1,0 0))", f.geometries().get(4).geometry().toWkt());
  }

  @Test
  void schemaFromXsd() {
    String xsd =
        """
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:gml="http://www.opengis.net/gml/3.2" xmlns:t="urn:t" targetNamespace="urn:t">
          <xs:element name="Road" type="t:RoadType" substitutionGroup="gml:AbstractFeature"/>
          <xs:complexType name="RoadType"><xs:complexContent><xs:extension base="gml:AbstractFeatureType"><xs:sequence>
            <xs:element name="name" type="xs:string" minOccurs="0"/>
            <xs:element name="lanes" type="xs:int"/>
            <xs:element name="length" type="xs:double" minOccurs="0"/>
            <xs:element name="open" type="xs:boolean"/>
            <xs:element name="built" type="xs:date" minOccurs="0"/>
            <xs:element name="code" minOccurs="0"><xs:simpleType><xs:restriction base="xs:string"><xs:maxLength value="12"/></xs:restriction></xs:simpleType></xs:element>
            <xs:element name="tags" type="xs:string" maxOccurs="unbounded"/>
            <xs:element name="centre" type="gml:CurvePropertyType"/>
          </xs:sequence></xs:extension></xs:complexContent></xs:complexType>
          <xs:element name="Other" type="xs:string"/>
        </xs:schema>
        """;
    var types = XsdSchema.parse(xsd);
    assertEquals(List.of("Road"), List.copyOf(types.keySet()));
    var road = types.get("Road");
    assertEquals(
        List.of(
            "name:STRING",
            "lanes:INTEGER",
            "length:REAL",
            "open:INTEGER",
            "built:DATE",
            "code:STRING",
            "tags:STRING_LIST"),
        road.fields().stream().map(x -> x.name() + ":" + x.type()).toList());
    assertEquals(12, road.fields().get(5).width());
    assertTrue(road.fields().get(0).nullable());
    assertFalse(road.fields().get(1).nullable());
    assertEquals(GeometryType.LINESTRING, road.geometryType());
  }

  @Test
  void writtenGeometriesReadBackInAllVersions() throws Exception {
    List<String> wkts =
        List.of(
            "POINT (10.5 59.25)",
            "POINT Z (1 2 3)",
            "LINESTRING (0 0, 1 1, 2 0)",
            "POLYGON ((0 0, 4 0, 4 4, 0 4, 0 0), (1 1, 2 1, 2 2, 1 2, 1 1))",
            "MULTIPOINT ((1 2), (3 4))",
            "MULTILINESTRING ((0 0, 1 1), (5 5, 6 6))",
            "MULTIPOLYGON (((0 0, 1 0, 1 1, 0 0)), ((5 5, 6 5, 6 6, 5 5)))",
            "GEOMETRYCOLLECTION (POINT (1 1), LINESTRING (0 0, 1 1))");
    for (GmlGeometryWriter.Version v : GmlGeometryWriter.Version.values()) {
      for (String srs : new String[] {null, "EPSG:4326", "urn:ogc:def:crs:EPSG::4326"}) {
        for (String wkt : wkts) {
          String xml = GmlGeometryWriter.write(Wkt.read(wkt), v, srs);
          var r =
              javax.xml.stream.XMLInputFactory.newFactory()
                  .createXMLStreamReader(new java.io.StringReader(xml));
          while (r.next() != javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
            // find the root
          }
          var result = GmlGeometries.read(r, null);
          assertEquals(srs, result.srsName());
          Geometry back = GmlSchema.orient(result.geometry(), result.srsName());
          assertEquals(Wkt.read(wkt).toWkt(), back.toWkt(), v + " " + srs + " " + wkt);
        }
      }
    }
  }

  @Test
  void writtenGmlDetails() {
    var poly = Wkt.read("POLYGON ((1 2, 3 2, 3 4, 1 2))");
    assertEquals(
        "<gml:Polygon xmlns:gml=\"http://www.opengis.net/gml\""
            + " srsName=\"EPSG:4326\"><gml:outerBoundaryIs><gml:LinearRing><gml:coordinates>1,2 3,2"
            + " 3,4 1,2</gml:coordinates></gml:LinearRing></gml:outerBoundaryIs></gml:Polygon>",
        GmlGeometryWriter.write(poly, GmlGeometryWriter.Version.GML2, "EPSG:4326"));
    assertEquals(
        "<gml:Polygon xmlns:gml=\"http://www.opengis.net/gml/3.2\" gml:id=\"g1\""
            + " srsName=\"urn:ogc:def:crs:EPSG::4326\"><gml:exterior><gml:LinearRing"
            + " gml:id=\"g2\"><gml:posList>2 1 2 3 4 3 2"
            + " 1</gml:posList></gml:LinearRing></gml:exterior></gml:Polygon>",
        GmlGeometryWriter.write(
            poly, GmlGeometryWriter.Version.GML32, "urn:ogc:def:crs:EPSG::4326")); // latitude first
  }
}
