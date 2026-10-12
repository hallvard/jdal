package dev.jdal.ops;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.Dataset;
import dev.jdal.core.Jdal;
import dev.jdal.core.Layer;
import dev.jdal.core.Query;
import dev.jdal.core.SpatialOp;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldType;
import dev.jdal.geom.Envelope;
import dev.jdal.geom.Geometry;
import dev.jdal.geom.GeometryType;
import dev.jdal.geom.Wkt;
import dev.jdal.srs.SpatialReference;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpatialFilterTest {

  static Geometry g(String wkt) {
    return Wkt.read(wkt);
  }

  static List<String> names(Layer l, Query q) {
    try (var s = l.features(q)) {
      return s.map(f -> (String) f.get("name")).toList();
    }
  }

  static Layer layer() {
    Dataset ds = Jdal.create("Memory", "mem", Map.of());
    Layer l =
        ds.createLayer(
            FeatureType.of(
                "t",
                GeometryType.UNKNOWN,
                SpatialReference.WGS84,
                List.of(FieldDefn.of("name", FieldType.STRING))),
            Map.of());
    String[][] rows = {
      {"inside", "POINT (5 5)"},
      {"outside", "POINT (50 50)"},
      {"diagonal", "LINESTRING (-5 0, 5 -5)"}, // envelope overlaps the square, the line does not
      {"crossing", "LINESTRING (5 5, 20 5)"},
      {"within", "POLYGON ((2 2, 4 2, 4 4, 2 4, 2 2))"},
      {"around", "POLYGON ((-5 -5, 15 -5, 15 15, -5 15, -5 -5))"},
      {"touching", "POINT (10 5)"},
      {"nogeom", null},
    };
    for (String[] r : rows) {
      Feature.Builder b = Feature.builder(l.type()).set("name", r[0]);
      if (r[1] != null) b.geometry(g(r[1]));
      l.insert(b.build());
    }
    return l;
  }

  static final String SQUARE = "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))";

  @Test
  void intersectsIsExact() {
    Layer l = layer();
    assertEquals(
        List.of("inside", "crossing", "within", "around", "touching"),
        names(l, Query.spatial(SpatialOp.INTERSECTS, g(SQUARE))));
    // the old envelope test lets the diagonal line through
    assertTrue(names(l, Query.bbox(new Envelope(0, 0, 10, 10))).contains("diagonal"));
  }

  @Test
  void otherPredicates() {
    Layer l = layer();
    assertEquals(List.of("inside", "within"), names(l, Query.spatial(SpatialOp.WITHIN, g(SQUARE))));
    assertEquals(List.of("around"), names(l, Query.spatial(SpatialOp.CONTAINS, g(SQUARE))));
    assertEquals(
        List.of("outside", "diagonal"), names(l, Query.spatial(SpatialOp.DISJOINT, g(SQUARE))));
    assertEquals(List.of("touching"), names(l, Query.spatial(SpatialOp.TOUCHES, g(SQUARE))));
    assertEquals(List.of("crossing"), names(l, Query.spatial(SpatialOp.CROSSES, g(SQUARE))));
    assertEquals(
        List.of("within"),
        names(l, Query.spatial(SpatialOp.EQUALS, g("POLYGON ((2 2, 2 4, 4 4, 4 2, 2 2))"))));
  }

  @Test
  void combinesWithOtherFilters() {
    Layer l = layer();
    Query q =
        Query.spatial(SpatialOp.INTERSECTS, g(SQUARE))
            .withBbox(new Envelope(0, 0, 6, 6))
            .withLimit(2);
    assertEquals(List.of("inside", "crossing"), names(l, q));
    assertEquals(new Envelope(0, 0, 6, 6), q.candidateBox());
    assertEquals(
        new Envelope(0, 0, 10, 10), Query.spatial(SpatialOp.WITHIN, g(SQUARE)).candidateBox());
    assertNull(Query.spatial(SpatialOp.DISJOINT, g(SQUARE)).candidateBox());
    assertEquals(
        3,
        l.count(
            Query.spatial(SpatialOp.INTERSECTS, g(SQUARE))
                .withFilter(f -> !f.get("name").equals("inside"))
                .withLimit(3)));
  }

  @Test
  void geometriesWithHolesAndCollections() {
    Layer l = layer();
    Geometry donut = g("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0), (3 3, 7 3, 7 7, 3 7, 3 3))");
    assertEquals(
        List.of("crossing", "around", "touching"),
        names(l, Query.spatial(SpatialOp.INTERSECTS, donut)).stream()
            .filter(n -> !n.equals("inside") && !n.equals("within"))
            .toList());
    assertFalse(
        names(l, Query.spatial(SpatialOp.INTERSECTS, donut)).contains("inside")); // in the hole
    assertEquals(
        List.of("inside", "within"),
        names(
                l,
                Query.spatial(
                    SpatialOp.INTERSECTS,
                    g("MULTIPOLYGON (((4 4, 6 4, 6 6, 4 6, 4 4)), ((2 2, 3 2, 3 3, 2 3, 2 2)))")))
            .stream()
            .filter(n -> n.equals("inside") || n.equals("within"))
            .toList());
  }

  @Test
  void convertsAllKinds() {
    for (String wkt :
        List.of(
            "POINT EMPTY",
            "POINT Z (1 2 3)",
            "LINESTRING (0 0, 1 1)",
            "MULTIPOINT ((1 1), (2 2))",
            "MULTILINESTRING ((0 0, 1 1), (2 2, 3 3))",
            "GEOMETRYCOLLECTION (POINT (1 1), LINESTRING (0 0, 1 1))",
            "POLYGON EMPTY",
            "POLYGON ((0 0, 1 0, 1 1, 0 0))")) {
      assertNotNull(Jts.toJts(g(wkt)), wkt);
    }
    assertEquals(
        "POLYGON ((0 0, 1 0, 1 1, 0 0))",
        Jts.toJts(g("POLYGON ((0 0, 1 0, 1 1))")).toText()); // open ring is closed
  }
}
