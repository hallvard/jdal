package dev.jdal.feature;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.geom.GeometryType;
import dev.jdal.geom.Point;
import dev.jdal.srs.SpatialReference;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureTest {

  static final FeatureType TYPE =
      FeatureType.of(
          "t",
          GeometryType.POINT,
          SpatialReference.WGS84,
          List.of(
              FieldDefn.of("i", FieldType.INTEGER),
              FieldDefn.of("l", FieldType.INTEGER64),
              FieldDefn.of("r", FieldType.REAL),
              FieldDefn.of("s", FieldType.STRING),
              FieldDefn.of("d", FieldType.DATE),
              FieldDefn.of("b", FieldType.INTEGER, FieldSubType.BOOLEAN),
              FieldDefn.of("il", FieldType.INTEGER_LIST)));

  @Test
  void buildsAndCoerces() {
    Feature f =
        Feature.builder(TYPE)
            .fid(3)
            .set("i", 4.9)
            .set("L", "12345678901")
            .set("r", 1)
            .set("s", 17)
            .set("d", "2020-02-29")
            .set("b", 1)
            .set("il", List.of(1L, 2L))
            .geometry(Point.xy(1, 2))
            .build();
    assertEquals(4, f.get("i"));
    assertEquals(12345678901L, f.get("l"));
    assertEquals(1.0, f.get("r"));
    assertEquals("17", f.get("s"));
    assertEquals(LocalDate.of(2020, 2, 29), f.get("d"));
    assertEquals(1, f.get("b"));
    assertTrue(f.getBoolean("b"));
    assertEquals(List.of(1, 2), f.get("il"));
    assertEquals(Point.xy(1, 2), f.geometry());
    assertTrue(f.hasFid());
  }

  @Test
  void isImmutable() {
    Feature f = Feature.builder(TYPE).set("i", 1).build();
    Feature g = f.with("i", 2);
    assertEquals(1, f.get("i"));
    assertEquals(2, g.get("i"));
    assertThrows(UnsupportedOperationException.class, () -> f.values().set(0, 5));
    assertFalse(f.hasFid());
  }

  @Test
  void rejectsBadValues() {
    assertThrows(IllegalArgumentException.class, () -> Feature.builder(TYPE).set("i", "abc"));
    assertThrows(
        IllegalArgumentException.class, () -> Feature.builder(TYPE).set("i", 3_000_000_000L));
    assertThrows(IllegalArgumentException.class, () -> Feature.builder(TYPE).set("nope", 1));
  }
}
