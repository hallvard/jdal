package dev.jdal.driver.csv;

import static dev.jdal.feature.FieldType.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.*;
import dev.jdal.feature.*;
import dev.jdal.testkit.Autotest;
import dev.jdal.testkit.Port;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Port of the type autodetection tests of ogr_csv.py (test_ogr_csv_32 and 33). */
class CsvAutodetectTest {

  static Dataset open(String file, String... options) {
    Map<String, String> o = new HashMap<>();
    for (String s : options) o.put(s.substring(0, s.indexOf('=')), s.substring(s.indexOf('=') + 1));
    return Jdal.open(Autotest.data("ogr/data/csv/" + file).toString(), false, o);
  }

  static final String FILE = "testtypeautodetect.csv";

  /** Compares types and (GDAL formatted) values of the first feature. */
  static void check(Layer l, FieldType[] types, Object[] values, int[] widths, int[] precisions) {
    Feature f = l.features().findFirst().orElseThrow();
    for (int i = 0; i < types.length; i++) {
      FieldDefn d = l.type().fields().get(i);
      assertEquals(types[i], d.type(), "type of field " + i + " " + d.name());
      if (widths != null) assertEquals(widths[i], d.width(), "width of field " + i);
      if (precisions != null) assertEquals(precisions[i], d.precision(), "precision of field " + i);
      Object v = f.get(i);
      Object expected = values[i];
      if (expected instanceof String s) {
        assertEquals(
            s, v == null ? null : FieldValues.format(d, v), "value of field " + i + " " + d.name());
      } else {
        assertEquals(expected, v, "value of field " + i + " " + d.name());
      }
    }
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_32")
  @Test
  void withoutLimitEverythingButTheRealColumnIsString() {
    String[] values = {
      "",
      "1.5",
      "1",
      "1.5",
      "2",
      "",
      "2014-09-27 19:01:00",
      "2014-09-27",
      "2014-09-27 20:00:00",
      "2014-09-27",
      "12:34:56",
      "a",
      "a",
      "1",
      "1",
      "1.5",
      "2014-09-27 19:01:00",
      "2014-09-27",
      "19:01:00",
      "2014-09-27T00:00:00Z"
    };
    for (String[] opts :
        List.of(
            new String[] {"AUTODETECT_TYPE=YES"},
            new String[] {"AUTODETECT_TYPE=YES", "AUTODETECT_SIZE_LIMIT=0"})) {
      try (Dataset ds = open(FILE, opts)) {
        Layer l = ds.layers().getFirst();
        Feature f = l.features().findFirst().orElseThrow();
        for (int i = 0; i < values.length; i++) {
          FieldDefn d = l.type().fields().get(i);
          assertEquals(i != 1 ? STRING : REAL, d.type(), "field " + i);
          assertEquals(0, d.width());
          if (i != 1) assertEquals(values[i], f.get(i), "field " + i);
        }
      }
    }
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_32")
  @Test
  void sizeLimit300() {
    FieldType[] types = {
      STRING, REAL, INTEGER, REAL, INTEGER, STRING, DATETIME, DATE, DATETIME, DATE, TIME, STRING,
      STRING, STRING, INTEGER, REAL, DATETIME, DATE, TIME, DATETIME
    };
    Object[] values = {
      "",
      1.5,
      1,
      1.5,
      2,
      "",
      "2014/09/27 19:01:00",
      "2014/09/27",
      "2014/09/27 20:00:00",
      "2014/09/27",
      "12:34:56",
      "a",
      "a",
      "1",
      1,
      1.5,
      "2014/09/27 19:01:00",
      "2014/09/27",
      "19:01:00",
      "2014/09/27 00:00:00+00"
    };
    try (Dataset ds =
        open(
            FILE,
            "AUTODETECT_TYPE=YES",
            "AUTODETECT_SIZE_LIMIT=300",
            "QUOTED_FIELDS_AS_STRING=YES")) {
      Layer l = ds.layers().getFirst();
      check(l, types, values, new int[20], null);
    }
    // without QUOTED_FIELDS_AS_STRING a quoted integer is an integer
    try (Dataset ds = open(FILE, "AUTODETECT_TYPE=YES", "AUTODETECT_SIZE_LIMIT=300")) {
      assertEquals(
          INTEGER,
          ds.layers()
              .getFirst()
              .type()
              .fields()
              .get(ds.layers().getFirst().type().requireFieldIndex("str3"))
              .type());
    }
  }

  static final FieldType[] TYPES_350 = {
    STRING, REAL, REAL, REAL, INTEGER, INTEGER, DATETIME, DATETIME, DATETIME, DATE, TIME, STRING,
    STRING, STRING, STRING, STRING, STRING, STRING, STRING, DATETIME
  };
  static final Object[] VALUES_350 = {
    "",
    1.5,
    1.0,
    1.5,
    2,
    null,
    "2014/09/27 19:01:00",
    "2014/09/27 00:00:00",
    "2014/09/27 20:00:00",
    "2014/09/27",
    "12:34:56",
    "a",
    "a",
    "1",
    "1",
    "1.5",
    "2014-09-27 19:01:00",
    "2014-09-27",
    "19:01:00",
    "2014/09/27 00:00:00+00"
  };

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_32")
  @Test
  void sizeLimit350WithWidths() {
    try (Dataset ds =
        open(
            FILE,
            "AUTODETECT_TYPE=YES",
            "AUTODETECT_SIZE_LIMIT=350",
            "QUOTED_FIELDS_AS_STRING=YES")) {
      check(ds.layers().getFirst(), TYPES_350, VALUES_350, new int[20], null);
    }
    int[] widths = {0, 3, 3, 3, 1, 1, 0, 0, 0, 0, 0, 1, 2, 1, 1, 3, 19, 10, 8, 0};
    int[] precisions = {0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    try (Dataset ds =
        open(
            FILE,
            "AUTODETECT_TYPE=YES",
            "AUTODETECT_SIZE_LIMIT=350",
            "AUTODETECT_WIDTH=YES",
            "QUOTED_FIELDS_AS_STRING=YES")) {
      check(ds.layers().getFirst(), TYPES_350, VALUES_350, widths, precisions);
    }
    int[] stringOnly = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 1, 1, 3, 19, 10, 8, 0};
    try (Dataset ds =
        open(
            FILE,
            "AUTODETECT_TYPE=YES",
            "AUTODETECT_SIZE_LIMIT=350",
            "AUTODETECT_WIDTH=STRING_ONLY",
            "QUOTED_FIELDS_AS_STRING=YES")) {
      check(ds.layers().getFirst(), TYPES_350, VALUES_350, stringOnly, new int[20]);
    }
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_32")
  @Test
  void valuesBeyondTheSampleThatDoNotFitGiveWarnings() {
    try (Dataset ds = open(FILE, "AUTODETECT_TYPE=YES", "AUTODETECT_SIZE_LIMIT=350")) {
      Layer l = ds.layers().getFirst();
      List<String> warnings = Diagnostics.capture(() -> l.features().forEach(f -> {}));
      assertFalse(warnings.isEmpty());
    }
  }

  @Port("autotest/ogr/ogr_csv.py::test_ogr_csv_33")
  @Test
  void booleanWordsAreAutodetected() {
    try (Dataset ds = open("testtypeautodetectboolean.csv", "AUTODETECT_TYPE=YES")) {
      Layer l = ds.layers().getFirst();
      Feature f = l.features().findFirst().orElseThrow();
      Object[] values = {1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1, "y"};
      for (int i = 0; i < values.length; i++) {
        FieldDefn d = l.type().fields().get(i);
        if (i < 10) assertEquals(FieldSubType.BOOLEAN, d.subType(), "field " + i);
        else assertNotEquals(FieldSubType.BOOLEAN, d.subType(), "field " + i);
        assertEquals(values[i], f.get(i), "field " + i);
      }
    }
  }
}
