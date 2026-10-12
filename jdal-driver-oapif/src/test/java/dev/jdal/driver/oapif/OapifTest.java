package dev.jdal.driver.oapif;

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
import dev.jdal.testkit.Port;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Ports of autotest/ogr/ogr_oapif.py against an in-process server that answers canned JSON. */
class OapifTest {

  record Reply(int status, String contentType, String body) {}

  HttpServer server;
  int port;
  final Map<String, Reply> replies = new HashMap<>(); // path?query -> reply
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
    String key =
        x.getRequestURI().getRawPath()
            + (x.getRequestURI().getRawQuery() == null
                ? ""
                : "?" + x.getRequestURI().getRawQuery());
    requests.add(key);
    Reply r = replies.getOrDefault(key, new Reply(404, null, ""));
    byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
    if (r.contentType() != null) x.getResponseHeaders().add("Content-Type", r.contentType());
    x.sendResponseHeaders(r.status(), body.length == 0 ? -1 : body.length);
    if (body.length > 0) x.getResponseBody().write(body);
    x.close();
  }

  void json(String pathAndQuery, String body) {
    replies.put(pathAndQuery, new Reply(200, "application/json", body));
  }

  void geojson(String pathAndQuery, String body) {
    replies.put(pathAndQuery, new Reply(200, "application/geo+json", body));
  }

  String url() {
    return "OAPIF:http://localhost:" + port + "/oapif";
  }

  static final String ONE_FEATURE =
      """
      { "type": "FeatureCollection", "features": [
        { "type": "Feature", "properties": { "foo": "bar" } } ] }\
      """;

  @Port("autotest/ogr/ogr_oapif.py::test_ogr_oapif_errors")
  @Test
  void errors() {
    JdalException e = assertThrows(JdalException.class, () -> Jdal.open(url()));
    assertTrue(e.getMessage().contains("HTTP error code : 404"), e.getMessage());

    replies.put("/oapif/collections", new Reply(404, null, "unavailable resource"));
    e = assertThrows(JdalException.class, () -> Jdal.open(url()));
    assertTrue(
        e.getMessage().contains("HTTP error code : 404, unavailable resource"), e.getMessage());

    for (Reply r :
        List.of(
            new Reply(200, null, "foo"), // no content type
            new Reply(200, "text/html", "foo"),
            new Reply(200, "application/json", "foo bar"), // invalid JSON
            new Reply(200, "application/json", "{}"), // no collections
            new Reply(200, "application/json", "{ \"collections\" : null }"),
            new Reply(200, "application/json", "{ \"collections\" : [ null, {} ] }"))) {
      replies.put("/oapif/collections", r);
      assertThrows(RuntimeException.class, () -> Jdal.open(url()), r.toString());
    }
  }

  @Port("autotest/ogr/ogr_oapif.py::test_ogr_oapif_collections_paging")
  @Test
  void collectionsArePaged() {
    json(
        "/oapif/collections",
        "{ \"collections\" : [ { \"id\": \"foo\" } ], \"links\": [ { \"rel\": \"next\", \"href\":"
            + " \"http://localhost:"
            + port
            + "/oapif/collections?next=my_mark\", \"type\": \"application/json\" } ]}");
    json("/oapif/collections?next=my_mark", "{ \"collections\" : [ { \"id\": \"bar\" } ] }");
    try (Dataset ds = Jdal.open(url())) {
      assertEquals(List.of("foo", "bar"), ds.layers().stream().map(Layer::name).toList());
    }
  }

  @Port("autotest/ogr/ogr_oapif.py::test_ogr_oapif_empty_layer_and_user_query_parameters")
  @Test
  void userQueryParametersAreKept() {
    json("/oapif/collections?FOO=BAR", "{ \"collections\" : [ { \"name\": \"foo\" } ] }");
    geojson(
        "/oapif/collections/foo/items?limit=20&FOO=BAR",
        "{ \"type\": \"FeatureCollection\", \"features\": [] }");
    try (Dataset ds = Jdal.open(url() + "?FOO=BAR")) {
      assertEquals(1, ds.layers().size());
      Layer l = ds.layers().getFirst();
      assertEquals("foo", l.name());
      assertEquals(0, l.type().fields().size());
    }
  }

  @Port("autotest/ogr/ogr_oapif.py::test_ogr_oapif_open_by_collection_and_legacy_wfs3_prefix")
  @Test
  void openingOneCollectionAndTheOldPrefix() {
    json("/oapif/collections/foo", "{ \"id\": \"foo\" }");
    geojson(
        "/oapif/collections/foo/items?limit=20",
        "{ \"type\": \"FeatureCollection\", \"features\": [] }");
    try (Dataset ds = Jdal.open("WFS3:http://localhost:" + port + "/oapif/collections/foo")) {
      assertEquals(1, ds.layers().size());
      assertEquals("foo", ds.layers().getFirst().name());
      assertEquals(0, ds.layers().getFirst().type().fields().size());
    }
    assertEquals(
        List.of(
            "/oapif/conformance",
            "/oapif/collections/foo",
            "/oapif/collections/foo/items?limit=20"),
        requests);
  }

  @Port("autotest/ogr/ogr_oapif.py::test_ogr_oapif_fc_links_next_geojson")
  @Test
  void featuresFollowNextLinksIncludingRelativeOnes() {
    json("/subdir/oapif/collections", "{ \"collections\" : [ { \"name\": \"foo\" } ] }");
    geojson("/subdir/oapif/collections/foo/items?limit=20", ONE_FEATURE);
    geojson(
        "/subdir/oapif/collections/foo/items?limit=1000",
        """
        { "type": "FeatureCollection",
          "links": [ { "rel": "next", "type": "application/geo+json", "href": "/subdir/oapif/foo_next" } ],
          "features": [ { "type": "Feature", "properties": { "foo": "bar" } } ] }\
        """);
    geojson(
        "/subdir/oapif/foo_next",
        """
        { "type": "FeatureCollection",
          "links": [ { "rel": "next", "type": "application/geo+json", "href": "http://localhost:%d/subdir/oapif/foo_next2" } ],
          "features": [ { "type": "Feature", "properties": { "foo": "baz" } } ] }\
        """
            .formatted(port));
    geojson(
        "/subdir/oapif/foo_next2",
        "{ \"type\": \"FeatureCollection\", \"features\": [ { \"type\": \"Feature\","
            + " \"properties\": { \"foo\": \"last\" } } ] }");
    try (Dataset ds = Jdal.open("OAPIF:http://localhost:" + port + "/subdir/oapif")) {
      Layer l = ds.layers().getFirst();
      assertEquals(1, l.type().fields().size());
      try (Stream<Feature> s = l.features()) {
        assertEquals(List.of("bar", "baz", "last"), s.map(f -> (String) f.get("foo")).toList());
      }
      assertEquals(List.of(0L, 1L, 2L), l.features().map(Feature::fid).toList());
    }
  }

  @Port("autotest/ogr/ogr_oapif.py::test_ogr_oapif_spatial_filter")
  @Test
  void spatialFilterIsSentToTheServer() {
    json(
        "/oapif/collections",
        "{ \"collections\" : [ { \"id\": \"foo\", \"extent\": { \"spatial\": { \"bbox\": [ [ -10,"
            + " 40, -100, 15, 50, 100 ] ] } } } ] }");
    geojson("/oapif/collections/foo/items?limit=20", ONE_FEATURE);
    geojson(
        "/oapif/collections/foo/items?limit=1000&bbox=2,49,3,50",
        """
        { "type": "FeatureCollection", "features": [
          { "type": "Feature", "properties": { "foo": "bar" }, "geometry": { "type": "Point", "coordinates": [ 2.5, 49.5 ] } } ] }\
        """);
    try (Dataset ds = Jdal.open(url())) {
      Layer l = ds.layers().getFirst();
      Envelope e = l.extent().orElseThrow();
      assertEquals(-10, e.minX());
      assertEquals(15, e.maxX());
      assertEquals(40, e.minY());
      assertEquals(50, e.maxY());
      try (Stream<Feature> s = l.features(Query.bbox(new Envelope(2, 49, 3, 50)))) {
        assertEquals(1, s.count());
      }
    }
    assertTrue(requests.contains("/oapif/collections/foo/items?limit=1000&bbox=2,49,3,50"));
  }

  @Port({
    "autotest/ogr/ogr_oapif.py::test_ogr_oapif_limit",
    "autotest/ogr/ogr_oapif.py::test_ogr_oapif_limit_from_numberMatched"
  })
  @Test
  void limitAndFeatureCount() {
    json("/oapif/collections", "{ \"collections\" : [ { \"id\": \"foo\" } ] }");
    geojson(
        "/oapif/collections/foo/items?limit=20",
        "{ \"type\": \"FeatureCollection\", \"numberMatched\": 1234, \"features\": [ { \"type\":"
            + " \"Feature\", \"properties\": { \"foo\": \"bar\" } } ] }");
    geojson(
        "/oapif/collections/foo/items?limit=5",
        "{ \"type\": \"FeatureCollection\", \"features\": [ { \"type\": \"Feature\","
            + " \"properties\": { \"foo\": \"a\" } }, { \"type\": \"Feature\", \"properties\": {"
            + " \"foo\": \"b\" } } ] }");
    try (Dataset ds = Jdal.open(url())) {
      Layer l = ds.layers().getFirst();
      assertEquals(1234, l.count()); // from numberMatched, without reading the features
      assertEquals(
          List.of(
              "/oapif/conformance", "/oapif/collections", "/oapif/collections/foo/items?limit=20"),
          requests);
      try (Stream<Feature> s = l.features(Query.ALL.withLimit(5))) {
        assertEquals(2, s.count()); // the server decides how many it has; limit=5 was sent
      }
      assertEquals("/oapif/collections/foo/items?limit=5", requests.getLast());
    }
  }

  @Port("autotest/ogr/ogr_oapif.py::test_ogr_oapif_attribute_filter")
  @Test
  void attributeFiltersAreAppliedHere() {
    json("/oapif/collections", "{ \"collections\" : [ { \"id\": \"foo\" } ] }");
    String page =
        "{ \"type\": \"FeatureCollection\", \"features\": ["
            + "{ \"type\": \"Feature\", \"properties\": { \"n\": 1, \"s\": \"a\" } },"
            + "{ \"type\": \"Feature\", \"properties\": { \"n\": 2, \"s\": \"b\" } },"
            + "{ \"type\": \"Feature\", \"properties\": { \"n\": 3, \"s\": \"c\" } } ] }";
    geojson("/oapif/collections/foo/items?limit=20", page);
    geojson("/oapif/collections/foo/items?limit=1000", page);
    try (Dataset ds = Jdal.open(url())) {
      Layer l = ds.layers().getFirst();
      assertEquals(FieldType.INTEGER, l.type().fields().get(0).type());
      try (Stream<Feature> s = l.features(Query.sql("n >= 2 AND s <> 'c'").withLimit(10))) {
        assertEquals(List.of("b"), s.map(f -> (String) f.get("s")).collect(Collectors.toList()));
      }
      assertEquals(2, l.count(Query.sql("n > 1")));
    }
  }

  @Port("autotest/ogr/ogr_oapif.py::test_ogr_oapif_collection_items_page_size")
  @Test
  void pageSizeAndDatetimeOptions() {
    json("/oapif/collections", "{ \"collections\" : [ { \"id\": \"foo\" } ] }");
    geojson("/oapif/collections/foo/items?limit=3", ONE_FEATURE);
    geojson("/oapif/collections/foo/items?limit=7&datetime=2020-01-01T00:00:00Z", ONE_FEATURE);
    try (Dataset ds =
        Jdal.open(
            url(),
            false,
            Map.of(
                "INITIAL_REQUEST_PAGE_SIZE",
                "3",
                "PAGE_SIZE",
                "7",
                "DATETIME",
                "2020-01-01T00:00:00Z"))) {
      Layer l = ds.layers().getFirst();
      assertEquals(1, l.type().fields().size());
      assertEquals(1, l.features().count());
    }
    assertEquals(
        "/oapif/collections/foo/items?limit=7&datetime=2020-01-01T00:00:00Z", requests.getLast());
  }

  @Test
  void readOnlyAndPrefixRequired() {
    assertThrows(JdalException.class, () -> Jdal.open(url(), true, Map.of()));
    assertThrows(JdalException.class, () -> Jdal.open("http://localhost:" + port + "/oapif"));
  }

  static final String CONFORMANCE =
      "{ \"conformsTo\": [ \"http://www.opengis.net/spec/ogcapi-features-1/1.0/conf/core\","
          + " \"http://www.opengis.net/spec/ogcapi-features-3/1.0/conf/filter\","
          + " \"http://www.opengis.net/spec/cql2/1.0/conf/cql2-text\" ] }";
  static final String THREE =
      "{ \"type\": \"FeatureCollection\", \"numberMatched\": 2, \"features\": ["
          + "{ \"type\": \"Feature\", \"properties\": { \"n\": 1, \"s\": \"a\" } },"
          + "{ \"type\": \"Feature\", \"properties\": { \"n\": 2, \"s\": \"b\" } },"
          + "{ \"type\": \"Feature\", \"properties\": { \"n\": 3, \"s\": \"c\" } } ] }";
  static final String TWO =
      "{ \"type\": \"FeatureCollection\", \"numberMatched\": 2, \"features\": ["
          + "{ \"type\": \"Feature\", \"properties\": { \"n\": 2, \"s\": \"b\" } },"
          + "{ \"type\": \"Feature\", \"properties\": { \"n\": 3, \"s\": \"c\" } } ] }";

  private void service() {
    json("/oapif/collections", "{ \"collections\" : [ { \"id\": \"foo\" } ] }");
    json("/oapif/conformance", CONFORMANCE);
    geojson("/oapif/collections/foo/items?limit=20", THREE);
  }

  @Test
  void attributeFilterIsSentAsCql2() {
    service();
    json("/oapif/collections/foo/queryables", "{ \"properties\": { \"n\": {}, \"s\": {} } }");
    String filtered =
        "/oapif/collections/foo/items?limit=1000&filter=n%20%3E%3D%202&filter-lang=cql2-text";
    geojson(filtered, TWO);
    geojson(
        "/oapif/collections/foo/items?limit=1&filter=n%20%3E%3D%202&filter-lang=cql2-text", TWO);
    try (Dataset ds = Jdal.open(url())) {
      Layer l = ds.layers().getFirst();
      assertEquals(
          List.of("b", "c"),
          l.features(Query.sql("n >= 2")).map(f -> (String) f.get("s")).toList());
      assertTrue(requests.contains(filtered));
      assertEquals(2, l.count(Query.sql("n >= 2"))); // numberMatched of a one feature page
      assertEquals(
          "/oapif/collections/foo/items?limit=1&filter=n%20%3E%3D%202&filter-lang=cql2-text",
          requests.getLast());
    }
  }

  @Test
  void aLimitGoesWithACompleteFilterOnly() {
    service();
    geojson(
        "/oapif/collections/foo/items?limit=1&filter=n%20%3E%3D%202&filter-lang=cql2-text",
        "{ \"type\": \"FeatureCollection\", \"features\": [{ \"type\": \"Feature\", \"properties\":"
            + " { \"n\": 2, \"s\": \"b\" } }] }");
    try (Dataset ds = Jdal.open(url())) {
      Layer l = ds.layers().getFirst();
      assertEquals(1, l.features(Query.sql("n >= 2").withLimit(1)).count());
      assertEquals(
          "/oapif/collections/foo/items?limit=1&filter=n%20%3E%3D%202&filter-lang=cql2-text",
          requests.getLast());
      // part of the filter cannot be sent: the limit must not be either
      geojson(
          "/oapif/collections/foo/items?limit=1000&filter=n%20%3E%3D%202&filter-lang=cql2-text",
          THREE);
      assertEquals(1, l.features(Query.sql("n >= 2 AND n + 1 > 3").withLimit(1)).count());
      assertEquals(
          "/oapif/collections/foo/items?limit=1000&filter=n%20%3E%3D%202&filter-lang=cql2-text",
          requests.getLast());
    }
  }

  @Test
  void aServiceThatRefusesTheFilterIsAskedWithoutIt() {
    service();
    replies.put(
        "/oapif/collections/foo/items?limit=1000&filter=n%20%3E%3D%202&filter-lang=cql2-text",
        new Reply(400, "text/plain", "bad filter"));
    geojson("/oapif/collections/foo/items?limit=1000", THREE);
    try (Dataset ds = Jdal.open(url())) {
      assertEquals(
          List.of("b", "c"),
          ds.layers()
              .getFirst()
              .features(Query.sql("n >= 2"))
              .map(f -> (String) f.get("s"))
              .toList());
    }
    assertEquals("/oapif/collections/foo/items?limit=1000", requests.getLast());
  }

  @Test
  void aServiceThatIgnoresTheFilterStillGivesTheRightFeatures() {
    service();
    geojson(
        "/oapif/collections/foo/items?limit=1000&filter=n%20%3E%3D%202&filter-lang=cql2-text",
        THREE);
    try (Dataset ds = Jdal.open(url())) {
      assertEquals(
          List.of("b", "c"),
          ds.layers()
              .getFirst()
              .features(Query.sql("n >= 2"))
              .map(f -> (String) f.get("s"))
              .toList());
    }
  }

  @Test
  void onlyQueryablePropertiesAreSent() {
    service();
    json("/oapif/collections/foo/queryables", "{ \"properties\": { \"s\": {} } }");
    geojson("/oapif/collections/foo/items?limit=1000", THREE);
    try (Dataset ds = Jdal.open(url())) {
      assertEquals(2, ds.layers().getFirst().features(Query.sql("n >= 2")).count());
    }
    assertEquals("/oapif/collections/foo/items?limit=1000", requests.getLast());
  }

  @Test
  void stringsAreComparedWithoutCaseLikeInOgrSql() {
    service();
    geojson(
        "/oapif/collections/foo/items?limit=1000&filter=CASEI%28s%29%20%3D%20CASEI%28%27B%27%29&filter-lang=cql2-text",
        TWO);
    try (Dataset ds = Jdal.open(url())) {
      assertEquals(
          List.of("b"),
          ds.layers()
              .getFirst()
              .features(Query.sql("s = 'B'"))
              .map(f -> (String) f.get("s"))
              .toList());
    }
  }

  @Test
  void withoutConformanceNothingIsSent() {
    json("/oapif/collections", "{ \"collections\" : [ { \"id\": \"foo\" } ] }");
    geojson("/oapif/collections/foo/items?limit=20", THREE);
    geojson("/oapif/collections/foo/items?limit=1000", THREE);
    try (Dataset ds = Jdal.open(url())) {
      assertEquals(2, ds.layers().getFirst().features(Query.sql("n >= 2")).count());
    }
    assertEquals("/oapif/collections/foo/items?limit=1000", requests.getLast());
  }

  static final String SQUARE = "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))";

  private static String cqlParam(String cql) {
    return java.net.URLEncoder.encode(cql, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private void spatialService() {
    json("/oapif/collections", "{ \"collections\" : [ { \"id\": \"foo\" } ] }");
    json("/oapif/conformance", CONFORMANCE);
    json(
        "/oapif/collections/foo/queryables",
        "{ \"properties\": { \"n\": {}, \"shape\": { \"$ref\":"
            + " \"https://geojson.org/schema/Geometry.json\" } } }");
    geojson("/oapif/collections/foo/items?limit=20", THREE);
  }

  static final String WITH_GEOMS =
      "{ \"type\": \"FeatureCollection\", \"features\": [{ \"type\": \"Feature\", \"properties\": {"
          + " \"n\": 1 }, \"geometry\": { \"type\": \"Point\", \"coordinates\": [5, 5] } },{"
          + " \"type\": \"Feature\", \"properties\": { \"n\": 2 }, \"geometry\": { \"type\":"
          + " \"LineString\", \"coordinates\": [[-5, 0], [5, -5]] } } ] }";

  @Test
  void aSpatialPredicateIsSentAndCheckedHere() {
    spatialService();
    String expected = "S_INTERSECTS(shape, " + dev.jdal.geom.Wkt.read(SQUARE).toWkt() + ")";
    String url =
        "/oapif/collections/foo/items?limit=1000&bbox=0,0,10,10&filter="
            + cqlParam(expected)
            + "&filter-lang=cql2-text";
    geojson(
        url,
        WITH_GEOMS); // a service that only compares envelopes would also return the diagonal line
    try (Dataset ds = Jdal.open(url())) {
      Layer l = ds.layers().getFirst();
      List<Object> ns =
          l.features(
                  Query.spatial(dev.jdal.core.SpatialOp.INTERSECTS, dev.jdal.geom.Wkt.read(SQUARE)))
              .map(f -> f.get("n"))
              .toList();
      assertEquals(List.of(1), ns); // the line misses the square: the exact test here removes it
    }
    assertTrue(requests.contains(url));
  }

  @Test
  void spatialAndAttributeConditionsShareOneFilter() {
    spatialService();
    String cql = "n >= 2 AND S_WITHIN(shape, " + dev.jdal.geom.Wkt.read(SQUARE).toWkt() + ")";
    String url =
        "/oapif/collections/foo/items?limit=1&bbox=0,0,10,10&filter="
            + cqlParam(cql)
            + "&filter-lang=cql2-text";
    geojson(url, "{ \"type\": \"FeatureCollection\", \"features\": [] }");
    try (Dataset ds = Jdal.open(url())) {
      Query q =
          Query.spatial(dev.jdal.core.SpatialOp.WITHIN, dev.jdal.geom.Wkt.read(SQUARE))
              .withWhere("n >= 2")
              .withLimit(1);
      assertEquals(0, ds.layers().getFirst().features(q).count());
    }
    assertTrue(requests.contains(url));
  }

  @Test
  void aTooLongPolygonIsSentAsItsEnvelopeOnly() {
    spatialService();
    StringBuilder wkt = new StringBuilder("POLYGON ((0 0");
    for (int i = 1; i <= 400; i++)
      wkt.append(", ").append(i / 40.0).append(' ').append(i % 2 == 0 ? 0 : 1);
    wkt.append(", 10 10, 0 10, 0 0))");
    geojson("/oapif/collections/foo/items?limit=1000&bbox=0,0,10,10", WITH_GEOMS);
    try (Dataset ds = Jdal.open(url())) {
      ds.layers()
          .getFirst()
          .features(
              Query.spatial(
                  dev.jdal.core.SpatialOp.INTERSECTS, dev.jdal.geom.Wkt.read(wkt.toString())))
          .count();
    }
    assertEquals("/oapif/collections/foo/items?limit=1000&bbox=0,0,10,10", requests.getLast());
  }

  @Test
  void aServiceThatRefusesTheSpatialPredicateStillGetsTheBox() {
    spatialService();
    String cql = "S_INTERSECTS(shape, " + dev.jdal.geom.Wkt.read(SQUARE).toWkt() + ")";
    replies.put(
        "/oapif/collections/foo/items?limit=1000&bbox=0,0,10,10&filter="
            + cqlParam(cql)
            + "&filter-lang=cql2-text",
        new Reply(400, "text/plain", "unknown function"));
    geojson("/oapif/collections/foo/items?limit=1000&bbox=0,0,10,10", WITH_GEOMS);
    try (Dataset ds = Jdal.open(url())) {
      assertEquals(
          1,
          ds.layers()
              .getFirst()
              .features(
                  Query.spatial(dev.jdal.core.SpatialOp.INTERSECTS, dev.jdal.geom.Wkt.read(SQUARE)))
              .count());
    }
    assertEquals("/oapif/collections/foo/items?limit=1000&bbox=0,0,10,10", requests.getLast());
  }
}
