package dev.jdal.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.memory.MemoryDataset;
import dev.jdal.feature.*;
import dev.jdal.geom.Envelope;
import dev.jdal.geom.GeometryType;
import dev.jdal.geom.Point;
import dev.jdal.srs.SpatialReference;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MemoryLayerTest {

  FeatureType type =
      FeatureType.of(
          "pts",
          GeometryType.POINT,
          SpatialReference.WGS84,
          List.of(FieldDefn.of("name", FieldType.STRING)));

  Layer layerWith(int n) {
    Layer l = new MemoryDataset("m").createLayer(type, Map.of());
    for (int i = 0; i < n; i++) {
      l.insert(Feature.builder(type).set("name", "p" + i).geometry(Point.xy(i, i)).build());
    }
    return l;
  }

  @Test
  void assignsFidsAndReads() {
    Layer l = layerWith(3);
    assertEquals(3, l.count());
    assertEquals("p2", l.feature(2).orElseThrow().get("name"));
    assertEquals(new Envelope(0, 0, 2, 2), l.extent().orElseThrow());
  }

  @Test
  void filtersBySpatialAndAttribute() {
    Layer l = layerWith(10);
    assertEquals(3, l.count(Query.bbox(new Envelope(2, 2, 4, 4))));
    assertEquals(1, l.count(Query.where(f -> "p7".equals(f.get("name")))));
    try (Stream<Feature> s = l.features(Query.ALL.withLimit(2))) {
      assertEquals(2, s.count());
    }
  }

  @Test
  void updateDeleteAndAddField() {
    Layer l = layerWith(2);
    l.update(l.feature(0).orElseThrow().with("name", "changed"));
    assertEquals("changed", l.feature(0).orElseThrow().get("name"));
    assertTrue(l.delete(1));
    assertFalse(l.delete(1));
    l.addField(FieldDefn.of("extra", FieldType.INTEGER));
    assertNull(l.feature(0).orElseThrow().get("extra"));
    assertEquals(2, l.type().fields().size());
  }

  @Test
  void rejectsWrongSchemaAndDuplicateFid() {
    Layer l = layerWith(1);
    FeatureType other = type.withName("other");
    assertThrows(JdalException.class, () -> l.insert(Feature.builder(other).build()));
    assertThrows(JdalException.class, () -> l.insert(Feature.builder(type).fid(0).build()));
  }

  @Test
  void memoryDriverIsRegistered() {
    assertTrue(Jdal.driver("memory").isPresent());
    assertThrows(JdalException.class, () -> Jdal.open("/does/not/exist.xyz"));
  }
}
