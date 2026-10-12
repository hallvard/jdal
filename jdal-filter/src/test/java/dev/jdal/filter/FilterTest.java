package dev.jdal.filter;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.feature.FeatureType;
import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldType;
import dev.jdal.filter.OgcFilter.Version;
import java.util.List;
import org.junit.jupiter.api.Test;

class FilterTest {

  static final FeatureType TYPE =
      FeatureType.of(
          "t",
          List.of(
              FieldDefn.of("eas_id", FieldType.INTEGER),
              FieldDefn.of("name", FieldType.STRING),
              FieldDefn.of("area", FieldType.REAL),
              FieldDefn.of("built", FieldType.DATE)));

  static String cql(String where) {
    return Cql2Text.translate(where, TYPE).text();
  }

  @Test
  void comparisons() {
    assertEquals("eas_id > 167", cql("eas_id > 167"));
    assertEquals("eas_id > 167", cql("167 < eas_id"));
    assertEquals(
        "area <= 1.5",
        cql("AREA <= 1.5")); // field names ignore case; the schema's spelling is not needed
    assertEquals("eas_id <> -3", cql("eas_id <> -3"));
    assertEquals("CASEI(name) = CASEI('a''b')", cql("name = 'a''b'"));
    assertEquals("CASEI(name) >= CASEI('M')", cql("name >= 'M'"));
  }

  @Test
  void likeBetweenInAndNull() {
    assertEquals("name LIKE 'A%'", cql("name LIKE 'A%'"));
    assertEquals("CASEI(name) LIKE CASEI('a%')", cql("name ILIKE 'a%'"));
    assertEquals("name LIKE 'A\\%%'", cql("name LIKE 'A\\%%' ESCAPE '\\'"));
    assertNull(cql("name LIKE 'A#%%' ESCAPE '#'"));
    assertEquals("eas_id BETWEEN 1 AND 5", cql("eas_id BETWEEN 1 AND 5"));
    assertEquals("eas_id IN (1, 2, 3)", cql("eas_id IN (1, 2, 3)"));
    assertEquals("CASEI(name) IN (CASEI('a'), CASEI('b'))", cql("name IN ('a', 'b')"));
    assertEquals("name IS NULL", cql("name IS NULL"));
    assertEquals("NOT (name IS NULL)", cql("name IS NOT NULL"));
    assertEquals("NOT (eas_id IN (1, 2))", cql("eas_id NOT IN (1, 2)"));
  }

  @Test
  void logicalOperators() {
    assertEquals("eas_id > 1 AND eas_id < 5", cql("eas_id > 1 AND eas_id < 5"));
    assertEquals(
        "(eas_id > 1 OR eas_id < -5) AND CASEI(name) = CASEI('x')",
        cql("(eas_id > 1 OR eas_id < -5) AND name = 'x'"));
    assertEquals("NOT (eas_id > 1 AND eas_id < 5)", cql("NOT (eas_id > 1 AND eas_id < 5)"));
  }

  @Test
  void whatCannotBeTranslated() {
    assertNull(cql("eas_id + 1 > 3")); // arithmetic
    assertNull(cql("nosuchfield = 1"));
    assertNull(cql("eas_id = '5'")); // OGR SQL converts the string; a service would not
    assertNull(cql("name = 5"));
    assertNull(cql("built > '2020-01-01'")); // dates are left to the client
    assertNull(cql("eas_id = name"));
    assertNull(cql("eas_id = NULL"));
    assertNull(cql("eas_id > 1 OR built > '2020-01-01'")); // an OR cannot be partial
    assertNull(cql("fid = 3"));
  }

  @Test
  void anAndMaySendOnlyItsTranslatablePart() {
    Translation t =
        Cql2Text.translate("eas_id > 1 AND built > '2020-01-01' AND name LIKE 'A%'", TYPE);
    assertEquals("eas_id > 1 AND name LIKE 'A%'", t.text());
    assertFalse(t.complete());
    assertTrue(Cql2Text.translate("eas_id > 1 AND name LIKE 'A%'", TYPE).complete());
  }

  @Test
  void queryablePropertiesOnly() {
    Translation t = Cql2Text.translate("eas_id > 1 AND area > 2", TYPE, n -> n.equals("area"));
    assertEquals("area > 2", t.text());
    assertFalse(t.complete());
    assertFalse(Cql2Text.translate("eas_id > 1", TYPE, n -> false).present());
  }

  @Test
  void quotingOfNames() {
    FeatureType t = FeatureType.of("t", List.of(FieldDefn.of("my field", FieldType.INTEGER)));
    assertEquals("\"my field\" = 1", Cql2Text.translate("\"my field\" = 1", t).text());
  }

  // ------------------------------------------------------------------ OGC filter encoding

  static String fes(String where) {
    Translation t = OgcFilter.translate(where, TYPE, Version.FES_2_0);
    return t.present() ? OgcFilter.filterElement(t.text(), Version.FES_2_0) : null;
  }

  @Test
  void fesComparisons() {
    assertEquals(
        "<fes:Filter xmlns:fes=\"http://www.opengis.net/fes/2.0\"><fes:PropertyIsGreaterThan>"
            + "<fes:ValueReference>eas_id</fes:ValueReference><fes:Literal>167</fes:Literal></fes:PropertyIsGreaterThan></fes:Filter>",
        fes("eas_id > 167"));
    assertEquals(
        "<fes:Filter xmlns:fes=\"http://www.opengis.net/fes/2.0\"><fes:PropertyIsEqualTo"
            + " matchCase=\"false\">"
            + "<fes:ValueReference>name</fes:ValueReference><fes:Literal>a&lt;b&amp;&quot;c</fes:Literal></fes:PropertyIsEqualTo></fes:Filter>",
        fes("name = 'a<b&\"c'"));
  }

  @Test
  void ogcOperators() {
    Version v = Version.FILTER_1_1;
    String and = OgcFilter.translate("eas_id > 1 AND name LIKE 'A%'", TYPE, v).text();
    assertEquals(
        "<ogc:And><ogc:PropertyIsGreaterThan><ogc:PropertyName>eas_id</ogc:PropertyName><ogc:Literal>1</ogc:Literal></ogc:PropertyIsGreaterThan><ogc:PropertyIsLike"
            + " wildCard=\"%\" singleChar=\"_\""
            + " escapeChar=\"\\\"><ogc:PropertyName>name</ogc:PropertyName><ogc:Literal>A%</ogc:Literal></ogc:PropertyIsLike></ogc:And>",
        and);
    assertEquals(
        "<ogc:Or><ogc:PropertyIsEqualTo><ogc:PropertyName>eas_id</ogc:PropertyName><ogc:Literal>1</ogc:Literal></ogc:PropertyIsEqualTo>"
            + "<ogc:PropertyIsEqualTo><ogc:PropertyName>eas_id</ogc:PropertyName><ogc:Literal>2</ogc:Literal></ogc:PropertyIsEqualTo></ogc:Or>",
        OgcFilter.translate("eas_id IN (1, 2)", TYPE, v).text());
    assertEquals(
        "<ogc:Not><ogc:PropertyIsNull><ogc:PropertyName>name</ogc:PropertyName></ogc:PropertyIsNull></ogc:Not>",
        OgcFilter.translate("name IS NOT NULL", TYPE, v).text());
    assertEquals(
        "<ogc:PropertyIsBetween><ogc:PropertyName>area</ogc:PropertyName><ogc:LowerBoundary><ogc:Literal>1</ogc:Literal></ogc:LowerBoundary>"
            + "<ogc:UpperBoundary><ogc:Literal>2</ogc:Literal></ogc:UpperBoundary></ogc:PropertyIsBetween>",
        OgcFilter.translate("area BETWEEN 1 AND 2", TYPE, v).text());
    assertEquals(
        "<ogc:PropertyIsLike wildCard=\"%\" singleChar=\"_\" escapeChar=\"\\\""
            + " matchCase=\"false\"><ogc:PropertyName>name</ogc:PropertyName><ogc:Literal>a%</ogc:Literal></ogc:PropertyIsLike>",
        OgcFilter.translate("name ILIKE 'a%'", TYPE, v).text());
  }

  @Test
  void filter10CannotIgnoreCase() {
    Version v = Version.FILTER_1_0;
    assertNull(OgcFilter.translate("name = 'x'", TYPE, v).text());
    assertNull(OgcFilter.translate("name ILIKE 'x%'", TYPE, v).text());
    assertNull(OgcFilter.translate("name BETWEEN 'a' AND 'c'", TYPE, v).text());
    assertEquals(
        "<ogc:PropertyIsLike wildCard=\"%\" singleChar=\"_\""
            + " escape=\"\\\"><ogc:PropertyName>name</ogc:PropertyName><ogc:Literal>x%</ogc:Literal></ogc:PropertyIsLike>",
        OgcFilter.translate("name LIKE 'x%'", TYPE, v).text());
    assertEquals(
        "<ogc:Filter xmlns:ogc=\"http://www.opengis.net/ogc\"></ogc:Filter>",
        OgcFilter.filterElement("", v));
    assertEquals(Version.FES_2_0, Version.forWfs("2.0.0"));
    assertEquals(Version.FILTER_1_0, Version.forWfs("1.0.0"));
    assertEquals(Version.FILTER_1_1, Version.forWfs("1.1.0"));
  }

  @Test
  void stringsAndNumbersInBetween() {
    assertEquals("CASEI(name) BETWEEN CASEI('a') AND CASEI('c')", cql("name BETWEEN 'a' AND 'c'"));
    assertNull(OgcFilter.translate("name BETWEEN 'a' AND 'c'", TYPE, Version.FES_2_0).text());
  }
}
