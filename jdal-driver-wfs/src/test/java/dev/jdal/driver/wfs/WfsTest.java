package dev.jdal.driver.wfs;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jdal.core.Dataset;
import dev.jdal.core.Jdal;
import dev.jdal.core.JdalException;
import dev.jdal.core.Layer;
import dev.jdal.core.Query;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FieldType;
import dev.jdal.geom.Envelope;
import dev.jdal.geom.GeometryType;
import dev.jdal.testkit.Port;
import dev.jdal.vsi.Vsi;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WFS against an in-process server with canned capabilities, schema and GML responses (after
 * ogr_wfs.py's fake servers).
 */
class WfsTest {

  HttpServer server;
  int port;
  final Map<String, String> replies = new HashMap<>(); // raw query -> body
  final Map<String, Integer> statuses = new HashMap<>();
  final List<String> requests = Collections.synchronizedList(new ArrayList<>());

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    port = server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void handle(HttpExchange x) throws IOException {
    String q = x.getRequestURI().getRawQuery();
    requests.add(q);
    String body = replies.get(q);
    int status = body == null ? 404 : statuses.getOrDefault(q, 200);
    byte[] b = (body == null ? "no such request" : body).getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().add("Content-Type", "text/xml");
    x.sendResponseHeaders(status, b.length);
    x.getResponseBody().write(b);
    x.close();
  }

  String caps(String version, String srsTag, String srs) {
    return """
    <wfs:WFS_Capabilities version="%s" xmlns:wfs="http://www.opengis.net/wfs" xmlns:ows="http://www.opengis.net/ows" xmlns:xlink="http://www.w3.org/1999/xlink">
      <ows:OperationsMetadata>
        <ows:Operation name="GetFeature"><ows:DCP><ows:HTTP><ows:Get xlink:href="http://localhost:%d/wfs?"/></ows:HTTP></ows:DCP></ows:Operation>
      </ows:OperationsMetadata>
      <wfs:FeatureTypeList>
        <wfs:FeatureType><wfs:Name>ns:my_layer</wfs:Name><wfs:Title>My layer</wfs:Title><wfs:%s>%s</wfs:%s>
          <ows:WGS84BoundingBox><ows:LowerCorner>-10 40</ows:LowerCorner><ows:UpperCorner>10 50</ows:UpperCorner></ows:WGS84BoundingBox>
        </wfs:FeatureType>
      </wfs:FeatureTypeList>
    </wfs:WFS_Capabilities>
    """
        .formatted(version, port, srsTag, srs, srsTag);
  }

  static final String XSD =
      """
      <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:gml="http://www.opengis.net/gml" xmlns:ns="http://ns" targetNamespace="http://ns">
        <xs:element name="my_layer" type="ns:my_layerType" substitutionGroup="gml:_Feature"/>
        <xs:complexType name="my_layerType"><xs:complexContent><xs:extension base="gml:AbstractFeatureType"><xs:sequence>
          <xs:element name="str" type="xs:string" minOccurs="0"/>
          <xs:element name="n" type="xs:int" minOccurs="0"/>
          <xs:element name="shape" type="gml:PointPropertyType" minOccurs="0"/>
        </xs:sequence></xs:extension></xs:complexContent></xs:complexType>
      </xs:schema>
      """;

  static String features(String collectionAttrs, String... members) {
    return "<wfs:FeatureCollection xmlns:wfs=\"http://www.opengis.net/wfs\""
        + " xmlns:gml=\"http://www.opengis.net/gml\" xmlns:ns=\"http://ns\" "
        + collectionAttrs
        + ">"
        + String.join("", members)
        + "</wfs:FeatureCollection>";
  }

  static String member(String id, String str, int n, String srsName, String pos) {
    return "<gml:featureMember><ns:my_layer gml:id=\""
        + id
        + "\"><ns:str>"
        + str
        + "</ns:str><ns:n>"
        + n
        + "</ns:n><ns:shape><gml:Point srsName=\""
        + srsName
        + "\"><gml:pos>"
        + pos
        + "</gml:pos></gml:Point></ns:shape></ns:my_layer></gml:featureMember>";
  }

  String url() {
    return "WFS:http://localhost:" + port + "/wfs";
  }

  static final String URN = "urn:ogc:def:crs:EPSG::4326";
  static final String DESCRIBE =
      "SERVICE=WFS&VERSION=1.1.0&REQUEST=DescribeFeatureType&TYPENAME=ns:my_layer";
  static final String GET = "SERVICE=WFS&VERSION=1.1.0&REQUEST=GetFeature&TYPENAME=ns:my_layer";

  @Port("autotest/ogr/ogr_wfs.py::test_ogr_wfs_vsimem_wfs110_minimal_instance")
  @Test
  void layersFromCapabilities() {
    replies.put("SERVICE=WFS&REQUEST=GetCapabilities", caps("1.1.0", "DefaultSRS", URN));
    try (Dataset ds = Jdal.open(url())) {
      assertEquals("WFS", ds.driver().name());
      assertEquals(List.of("ns:my_layer"), ds.layers().stream().map(Layer::name).toList());
      assertEquals(-10, ds.layers().getFirst().extent().orElseThrow().minX());
    }
    assertEquals(List.of("SERVICE=WFS&REQUEST=GetCapabilities"), requests);
  }

  @Port("autotest/ogr/ogr_wfs.py::test_ogr_wfs_vsimem_wfs110_one_layer_getfeature")
  @Test
  void schemaFeaturesAndAxisOrder() {
    replies.put("SERVICE=WFS&REQUEST=GetCapabilities", caps("1.1.0", "DefaultSRS", URN));
    replies.put(DESCRIBE, XSD);
    replies.put(
        GET,
        features(
            "",
            member("my_layer.1", "foo", 1, URN, "49 2"),
            member("my_layer.2", "bar", 2, URN, "50 3")));
    try (Dataset ds = Jdal.open(url())) {
      Layer l = ds.layers().getFirst();
      assertEquals(
          List.of("gml_id", "str", "n"), l.type().fields().stream().map(f -> f.name()).toList());
      assertEquals(FieldType.INTEGER, l.type().fields().get(2).type());
      assertEquals(GeometryType.POINT, l.type().geometryType());
      assertEquals(4326, l.type().srs().epsg());
      List<Feature> fs = l.features().toList();
      assertEquals(2, fs.size());
      assertEquals("my_layer.1", fs.get(0).get("gml_id"));
      assertEquals("foo", fs.get(0).get("str"));
      assertEquals(2, fs.get(1).get("n"));
      assertEquals("POINT (2 49)", fs.get(0).geometry().toWkt()); // urn: latitude first, stored x,y
    }
    assertTrue(requests.contains(DESCRIBE));
  }

  @Port("autotest/ogr/ogr_wfs.py::test_ogr_wfs_vsimem_wfs110_one_layer_filter_spatial_ops")
  @Test
  void spatialFilterIsSentInTheOrderOfTheCrs() {
    replies.put("SERVICE=WFS&REQUEST=GetCapabilities", caps("1.1.0", "DefaultSRS", URN));
    replies.put(DESCRIBE, XSD);
    replies.put(
        GET + "&BBOX=49,2,50,3," + URN,
        features("", member("my_layer.1", "foo", 1, URN, "49.5 2.5")));
    try (Dataset ds = Jdal.open(url())) {
      Layer l = ds.layers().getFirst();
      assertEquals(1, l.features(Query.bbox(new Envelope(2, 49, 3, 50))).count());
    }
    assertTrue(requests.contains(GET + "&BBOX=49,2,50,3," + URN));
  }

  @Port("autotest/ogr/ogr_wfs.py::test_ogr_wfs_vsimem_wfs110_one_layer_getfeaturecount_with_hits")
  @Test
  void featureCountFromHits() {
    replies.put("SERVICE=WFS&REQUEST=GetCapabilities", caps("1.1.0", "DefaultSRS", URN));
    replies.put(GET + "&RESULTTYPE=hits", features("numberOfFeatures=\"3\""));
    try (Dataset ds = Jdal.open(url())) {
      assertEquals(3, ds.layers().getFirst().count());
    }
  }

  @Port("autotest/ogr/ogr_wfs.py::test_ogr_wfs_vsimem_wfs110_one_layer_exception_getfeature")
  @Test
  void exceptionReportsAreErrors() {
    replies.put("SERVICE=WFS&REQUEST=GetCapabilities", caps("1.1.0", "DefaultSRS", URN));
    replies.put(DESCRIBE, XSD);
    replies.put(
        GET,
        "<ows:ExceptionReport xmlns:ows=\"http://www.opengis.net/ows\"><ows:Exception"
            + " exceptionCode=\"NoApplicableCode\"><ows:ExceptionText>something went"
            + " wrong</ows:ExceptionText></ows:Exception></ows:ExceptionReport>");
    try (Dataset ds = Jdal.open(url())) {
      JdalException e =
          assertThrows(JdalException.class, () -> ds.layers().getFirst().features().count());
      assertTrue(e.getMessage().contains("something went wrong"), e.getMessage());
    }
  }

  @Port("autotest/ogr/ogr_wfs.py::test_ogr_wfs_vsimem_wfs110_one_layer_missing_describefeaturetype")
  @Test
  void schemaFromAFeatureWhenDescribeFeatureTypeFails() {
    replies.put("SERVICE=WFS&REQUEST=GetCapabilities", caps("1.1.0", "DefaultSRS", URN));
    replies.put(GET + "&MAXFEATURES=1", features("", member("my_layer.1", "foo", 1, URN, "49 2")));
    try (Dataset ds = Jdal.open(url())) {
      Layer l = ds.layers().getFirst();
      assertEquals(
          List.of("gml_id", "str", "n"), l.type().fields().stream().map(f -> f.name()).toList());
      assertEquals(FieldType.INTEGER, l.type().fields().get(2).type());
    }
  }

  @Test
  void wfs20WithPagingAndLimits() {
    replies.put("SERVICE=WFS&REQUEST=GetCapabilities", caps("2.0.0", "DefaultCRS", URN));
    String describe = "SERVICE=WFS&VERSION=2.0.0&REQUEST=DescribeFeatureType&TYPENAMES=ns:my_layer";
    String get = "SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature&TYPENAMES=ns:my_layer";
    replies.put(describe, XSD);
    replies.put(
        get + "&COUNT=2&STARTINDEX=0",
        features("", member("a.1", "a", 1, URN, "1 1"), member("a.2", "b", 2, URN, "2 2")));
    replies.put(get + "&COUNT=2&STARTINDEX=2", features("", member("a.3", "c", 3, URN, "3 3")));
    try (Dataset ds = Jdal.open(url(), false, Map.of("PAGING_ALLOWED", "YES", "PAGE_SIZE", "2"))) {
      Layer l = ds.layers().getFirst();
      assertEquals(List.of("a", "b", "c"), l.features().map(f -> (String) f.get("str")).toList());
    }
    replies.put(get + "&COUNT=1", features("", member("a.1", "a", 1, URN, "1 1")));
    try (Dataset ds = Jdal.open(url())) {
      assertEquals(1, ds.layers().getFirst().features(Query.ALL.withLimit(1)).count());
    }
    assertTrue(requests.contains(get + "&COUNT=1"));
  }

  @Test
  void attributeFiltersAreAppliedHere() {
    replies.put("SERVICE=WFS&REQUEST=GetCapabilities", caps("1.1.0", "DefaultSRS", URN));
    replies.put(DESCRIBE, XSD);
    replies.put(
        GET,
        features(
            "",
            member("a.1", "a", 1, URN, "1 1"),
            member("a.2", "b", 2, URN, "2 2"),
            member("a.3", "c", 3, URN, "3 3")));
    try (Dataset ds = Jdal.open(url())) {
      Layer l = ds.layers().getFirst();
      assertEquals(
          List.of("b", "c"),
          l.features(Query.sql("n >= 2")).map(f -> (String) f.get("str")).toList());
    }
  }

  @Port({
    "autotest/ogr/ogr_wfs.py::test_ogr_wfs_vsimem_fail_because_exception",
    "autotest/ogr/ogr_wfs.py::test_ogr_wfs_vsimem_fail_because_no_WFS_Capabilities",
    "autotest/ogr/ogr_wfs.py::test_ogr_wfs_vsimem_fail_because_invalid_xml_capabilities",
    "autotest/ogr/ogr_wfs.py::test_ogr_wfs_vsimem_fail_because_missing_featuretypelist",
    "autotest/ogr/ogr_wfs.py::test_ogr_wfs_vsimem_fail_because_empty_response"
  })
  @Test
  void badCapabilities() {
    String q = "SERVICE=WFS&REQUEST=GetCapabilities";
    assertThrows(JdalException.class, () -> Jdal.open(url())); // 404
    replies.put(q, "");
    assertThrows(JdalException.class, () -> Jdal.open(url()));
    replies.put(q, "<foo/>");
    assertThrows(JdalException.class, () -> Jdal.open(url()));
    replies.put(q, "<invalid_xml");
    assertThrows(JdalException.class, () -> Jdal.open(url()));
    replies.put(
        q,
        "<ows:ExceptionReport"
            + " xmlns:ows=\"http://www.opengis.net/ows\"><ows:Exception><ows:ExceptionText>denied</ows:ExceptionText></ows:Exception></ows:ExceptionReport>");
    assertTrue(
        assertThrows(JdalException.class, () -> Jdal.open(url())).getMessage().contains("denied"));
    replies.put(
        q, "<wfs:WFS_Capabilities xmlns:wfs=\"http://www.opengis.net/wfs\" version=\"1.1.0\"/>");
    assertThrows(JdalException.class, () -> Jdal.open(url()));
  }

  @Port("autotest/ogr/ogr_wfs.py::test_ogr_wfs_getcapabilitiesfile")
  @Test
  void capabilitiesFile() throws IOException {
    Vsi.global().writeString("/vsimem/wfs_caps.xml", caps("1.1.0", "DefaultSRS", URN));
    replies.put(DESCRIBE, XSD);
    replies.put(GET + "&MAXFEATURES=5", features("", member("a.1", "a", 1, URN, "1 1")));
    replies.put(GET, features("", member("a.1", "a", 1, URN, "1 1")));
    try (Dataset ds =
        Jdal.open("WFS:/vsimem/wfs_caps.xml")) { // the endpoint is the one in the capabilities
      assertEquals(1, ds.layers().getFirst().count(Query.ALL.withLimit(5)));
      assertEquals("a", ds.layers().getFirst().features().findFirst().orElseThrow().get("str"));
    }
    Vsi.global().delete("/vsimem/wfs_caps.xml");
  }

  @Test
  void identifiesWfsUrls() {
    assertTrue(
        new WfsDriver()
            .identify(
                new dev.jdal.core.OpenInfo(
                    "http://x/y?SERVICE=WFS&REQUEST=GetCapabilities", Map.of())));
    assertFalse(new WfsDriver().identify(new dev.jdal.core.OpenInfo("http://x/y", Map.of())));
  }

  static String enc(String xml) {
    return java.net.URLEncoder.encode(xml, StandardCharsets.UTF_8).replace("+", "%20");
  }

  static final String FES_N_GE_2 =
      "<fes:Filter"
          + " xmlns:fes=\"http://www.opengis.net/fes/2.0\"><fes:PropertyIsGreaterThanOrEqualTo>"
          + "<fes:ValueReference>n</fes:ValueReference><fes:Literal>2</fes:Literal></fes:PropertyIsGreaterThanOrEqualTo></fes:Filter>";

  private String threeFeatures20() {
    return features(
        "",
        member("a.1", "a", 1, URN, "1 1"),
        member("a.2", "b", 2, URN, "2 2"),
        member("a.3", "c", 3, URN, "3 3"));
  }

  private void wfs20() {
    replies.put("SERVICE=WFS&REQUEST=GetCapabilities", caps("2.0.0", "DefaultCRS", URN));
    replies.put("SERVICE=WFS&VERSION=2.0.0&REQUEST=DescribeFeatureType&TYPENAMES=ns:my_layer", XSD);
  }

  static final String GET20 = "SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature&TYPENAMES=ns:my_layer";

  @Test
  void attributeFilterIsSentAsOgcFilter() {
    wfs20();
    String filtered = GET20 + "&FILTER=" + enc(FES_N_GE_2);
    replies.put(
        filtered,
        features("", member("a.2", "b", 2, URN, "2 2"), member("a.3", "c", 3, URN, "3 3")));
    try (Dataset ds = Jdal.open(url())) {
      assertEquals(
          List.of("b", "c"),
          ds.layers()
              .getFirst()
              .features(Query.sql("n >= 2"))
              .map(f -> (String) f.get("str"))
              .toList());
    }
    assertEquals(filtered, requests.getLast());
  }

  @Test
  void aCompleteFilterAllowsALimitAndAHitsCount() {
    wfs20();
    replies.put(
        GET20 + "&COUNT=1&FILTER=" + enc(FES_N_GE_2),
        features("", member("a.2", "b", 2, URN, "2 2")));
    replies.put(
        GET20 + "&RESULTTYPE=hits&FILTER=" + enc(FES_N_GE_2), features("numberMatched=\"2\""));
    try (Dataset ds = Jdal.open(url())) {
      Layer l = ds.layers().getFirst();
      assertEquals(1, l.features(Query.sql("n >= 2").withLimit(1)).count());
      assertEquals(2, l.count(Query.sql("n >= 2")));
    }
  }

  @Test
  void aServerThatRefusesTheFilterIsAskedWithoutIt() {
    wfs20();
    replies.put(
        GET20 + "&FILTER=" + enc(FES_N_GE_2),
        "<ows:ExceptionReport"
            + " xmlns:ows=\"http://www.opengis.net/ows\"><ows:Exception><ows:ExceptionText>filters"
            + " not supported</ows:ExceptionText></ows:Exception></ows:ExceptionReport>");
    replies.put(GET20, threeFeatures20());
    try (Dataset ds = Jdal.open(url())) {
      assertEquals(
          List.of("b", "c"),
          ds.layers()
              .getFirst()
              .features(Query.sql("n >= 2").withLimit(5))
              .map(f -> (String) f.get("str"))
              .toList());
    }
    assertEquals(GET20, requests.getLast());
  }

  @Test
  void aSpatialFilterTakesPrecedenceOverAnAttributeFilter() {
    wfs20();
    replies.put(GET20 + "&BBOX=1,0,3,4," + URN, threeFeatures20());
    try (Dataset ds = Jdal.open(url())) {
      // kvp requests cannot have both BBOX and FILTER: the attribute filter is applied here
      assertEquals(
          2,
          ds.layers()
              .getFirst()
              .features(Query.sql("n >= 2").withBbox(new Envelope(0, 1, 4, 3)))
              .count());
    }
    assertEquals(GET20 + "&BBOX=1,0,3,4," + URN, requests.getLast());
  }

  @Test
  void stringsNeedMatchCaseWhichFilter10LacksAndFilterThatCannotBeSentIsApplied() {
    replies.put("SERVICE=WFS&REQUEST=GetCapabilities", caps("1.1.0", "DefaultSRS", URN));
    replies.put(DESCRIBE, XSD);
    String ogc =
        "<ogc:Filter xmlns:ogc=\"http://www.opengis.net/ogc\"><ogc:PropertyIsEqualTo"
            + " matchCase=\"false\"><ogc:PropertyName>str</ogc:PropertyName><ogc:Literal>B</ogc:Literal></ogc:PropertyIsEqualTo></ogc:Filter>";
    replies.put(GET + "&FILTER=" + enc(ogc), features("", member("a.2", "b", 2, URN, "2 2")));
    try (Dataset ds = Jdal.open(url())) {
      assertEquals(
          List.of("b"),
          ds.layers()
              .getFirst()
              .features(Query.sql("str = 'B'"))
              .map(f -> (String) f.get("str"))
              .toList());
    }
    assertTrue(requests.contains(GET + "&FILTER=" + enc(ogc)));
  }

  static final dev.jdal.geom.Geometry SQUARE =
      dev.jdal.geom.Wkt.read("POLYGON ((2 49, 3 49, 3 50, 2 50, 2 49))");

  private String spatialFilter11(dev.jdal.core.SpatialOp op) {
    String gml =
        dev.jdal.gml.GmlGeometryWriter.write(
            SQUARE, dev.jdal.gml.GmlGeometryWriter.Version.GML31, URN);
    return dev.jdal.filter.OgcFilter.filterElement(
        dev.jdal.filter.OgcFilter.spatial(OgcFilterVersion.V11, op, "shape", gml),
        OgcFilterVersion.V11);
  }

  private interface OgcFilterVersion {
    dev.jdal.filter.OgcFilter.Version V11 = dev.jdal.filter.OgcFilter.Version.FILTER_1_1;
    dev.jdal.filter.OgcFilter.Version FES = dev.jdal.filter.OgcFilter.Version.FES_2_0;
  }

  @Test
  void aSpatialPredicateGoesIntoTheFilter() {
    replies.put("SERVICE=WFS&REQUEST=GetCapabilities", caps("1.1.0", "DefaultSRS", URN));
    replies.put(DESCRIBE, XSD);
    String url = GET + "&FILTER=" + enc(spatialFilter11(dev.jdal.core.SpatialOp.WITHIN));
    replies.put(
        url,
        features("", member("a.1", "a", 1, URN, "49.5 2.5"), member("a.2", "b", 2, URN, "60 20")));
    try (Dataset ds = Jdal.open(url())) {
      Layer l = ds.layers().getFirst();
      // the server (here) also returns a point outside; the exact test removes it
      assertEquals(
          List.of("a"),
          l.features(Query.spatial(dev.jdal.core.SpatialOp.WITHIN, SQUARE))
              .map(f -> (String) f.get("str"))
              .toList());
    }
    assertTrue(requests.contains(url));
    assertFalse(requests.stream().anyMatch(r -> r.contains("BBOX")));
  }

  @Test
  void spatialAndAttributeConditionsAreAnded() {
    replies.put("SERVICE=WFS&REQUEST=GetCapabilities", caps("2.0.0", "DefaultCRS", URN));
    replies.put("SERVICE=WFS&VERSION=2.0.0&REQUEST=DescribeFeatureType&TYPENAMES=ns:my_layer", XSD);
    String gml =
        dev.jdal.gml.GmlGeometryWriter.write(
            SQUARE, dev.jdal.gml.GmlGeometryWriter.Version.GML32, URN);
    String spatial =
        dev.jdal.filter.OgcFilter.spatial(
            OgcFilterVersion.FES, dev.jdal.core.SpatialOp.INTERSECTS, "shape", gml);
    String attribute =
        dev.jdal.filter.OgcFilter.translate("n >= 2", ds0Type(), OgcFilterVersion.FES).text();
    String filter =
        dev.jdal.filter.OgcFilter.filterElement(
            dev.jdal.filter.OgcFilter.and(OgcFilterVersion.FES, List.of(spatial, attribute)),
            OgcFilterVersion.FES);
    String url = GET20 + "&COUNT=1&FILTER=" + enc(filter);
    replies.put(url, features("", member("a.2", "b", 2, URN, "49.5 2.5")));
    try (Dataset ds = Jdal.open(url())) {
      Query q =
          Query.spatial(dev.jdal.core.SpatialOp.INTERSECTS, SQUARE)
              .withWhere("n >= 2")
              .withLimit(1);
      assertEquals(1, ds.layers().getFirst().features(q).count());
    }
    assertTrue(requests.contains(url));
  }

  private static dev.jdal.feature.FeatureType ds0Type() {
    return dev.jdal.feature.FeatureType.of(
        "my_layer", List.of(dev.jdal.feature.FieldDefn.of("n", FieldType.INTEGER)));
  }

  @Test
  void aServerThatRefusesTheSpatialFilterGetsTheBox() {
    replies.put("SERVICE=WFS&REQUEST=GetCapabilities", caps("1.1.0", "DefaultSRS", URN));
    replies.put(DESCRIBE, XSD);
    replies.put(
        GET + "&FILTER=" + enc(spatialFilter11(dev.jdal.core.SpatialOp.INTERSECTS)),
        "<ows:ExceptionReport"
            + " xmlns:ows=\"http://www.opengis.net/ows\"><ows:Exception><ows:ExceptionText>no"
            + " spatial filters</ows:ExceptionText></ows:Exception></ows:ExceptionReport>");
    replies.put(
        GET + "&BBOX=49,2,50,3," + URN, features("", member("a.1", "a", 1, URN, "49.5 2.5")));
    try (Dataset ds = Jdal.open(url())) {
      assertEquals(
          1,
          ds.layers()
              .getFirst()
              .features(Query.spatial(dev.jdal.core.SpatialOp.INTERSECTS, SQUARE))
              .count());
    }
    assertEquals(GET + "&BBOX=49,2,50,3," + URN, requests.getLast());
  }

  @Test
  void withoutAnApplicationSchemaOnlyTheBoxIsSent() {
    replies.put("SERVICE=WFS&REQUEST=GetCapabilities", caps("1.1.0", "DefaultSRS", URN));
    replies.put(
        GET + "&MAXFEATURES=1",
        features("", member("a.1", "a", 1, URN, "49 2"))); // schema from a feature
    replies.put(
        GET + "&BBOX=49,2,50,3," + URN, features("", member("a.1", "a", 1, URN, "49.5 2.5")));
    try (Dataset ds = Jdal.open(url())) {
      assertEquals(
          1,
          ds.layers()
              .getFirst()
              .features(Query.spatial(dev.jdal.core.SpatialOp.INTERSECTS, SQUARE))
              .count());
    }
    assertEquals(GET + "&BBOX=49,2,50,3," + URN, requests.getLast());
  }
}
