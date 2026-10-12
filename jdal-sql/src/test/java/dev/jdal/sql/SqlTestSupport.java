package dev.jdal.sql;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.Dataset;
import dev.jdal.core.Jdal;
import dev.jdal.core.Layer;
import dev.jdal.core.Query;
import dev.jdal.feature.Feature;
import dev.jdal.testkit.Autotest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Helpers mirroring {@code ogrtest} of GDAL's autotest. */
final class SqlTestSupport {

  private SqlTestSupport() {}

  /** The autotest/ogr/data directory (shapefiles and dbf tables such as poly and idlink). */
  static Dataset dataDs() {
    return Jdal.open(Autotest.data("ogr/data").toString());
  }

  static Dataset open(String relative) {
    return Jdal.open(Autotest.data("ogr/data/" + relative).toString());
  }

  static List<Feature> all(Layer l) {
    try (Stream<Feature> s = l.features()) {
      return s.toList();
    }
  }

  static long countWhere(Layer l, String where) {
    return l.count(Query.sql(where));
  }

  /** Values of one field of all features. */
  static List<Object> values(Layer l, String field) {
    List<Object> out = new ArrayList<>();
    for (Feature f : all(l)) out.add(f.get(field));
    return out;
  }

  /**
   * ogrtest.check_features_against_list: numbers compare as numbers, everything else with equals.
   */
  static void check(Layer l, String field, Object... expected) {
    List<Object> got = values(l, field);
    assertEquals(expected.length, got.size(), "number of features: " + got);
    for (int i = 0; i < expected.length; i++) {
      Object e = expected[i], g = got.get(i);
      if (e instanceof Number en && g instanceof Number gn) {
        assertEquals(en.doubleValue(), gn.doubleValue(), 1e-9, field + "[" + i + "]");
      } else {
        assertEquals(e, g, field + "[" + i + "]");
      }
    }
  }

  static void checkList(List<?> got, Object... expected) {
    assertEquals(Arrays.asList(expected), got);
  }
}
