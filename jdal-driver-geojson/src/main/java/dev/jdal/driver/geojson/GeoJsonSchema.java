package dev.jdal.driver.geojson;

import dev.jdal.core.JdalException;
import dev.jdal.feature.*;
import dev.jdal.geom.Dimension;
import dev.jdal.geom.Envelope;
import dev.jdal.geom.Geometry;
import dev.jdal.geom.GeometryType;
import dev.jdal.json.*;
import dev.jdal.srs.SpatialReference;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** First pass over a document: infers the schema and gathers count and extent. */
final class GeoJsonSchema {

  final FeatureType type;
  final boolean idAsField;
  final long count;
  final Envelope extent;

  /** Skip properties that are not in the schema (pages of a service can differ). */
  boolean lenient;

  private GeoJsonSchema(FeatureType type, boolean idAsField, long count, Envelope extent) {
    this.type = type;
    this.idAsField = idAsField;
    this.count = count;
    this.extent = extent;
  }

  static GeoJsonSchema scan(GeoJsonSource source, String defaultName) {
    Map<String, Inferred> props = new LinkedHashMap<>();
    GeometryType geomType = null;
    Dimension dim = Dimension.XY;
    boolean hasStringId = false;
    long count = 0;
    Envelope extent = Envelope.EMPTY;
    Map<String, JsonValue> top;
    try (FeatureNodes nodes = new FeatureNodes(source.open())) {
      while (nodes.hasNext()) {
        JsonObject f = nodes.next();
        count++;
        JsonValue g = f.path("geometry");
        if (g instanceof JsonObject) {
          Geometry geom = readGeometry(g, count);
          geomType =
              geomType == null
                  ? geom.type()
                  : geomType == geom.type() ? geomType : GeometryType.UNKNOWN;
          dim = dim.union(geom.dimension());
          extent = extent.union(geom.envelope());
        }
        if (f.path("properties") instanceof JsonObject p) {
          p.members().forEach((k, v) -> props.computeIfAbsent(k, x -> new Inferred()).add(v));
        }
        if (f.path("id") instanceof JsonString) hasStringId = true;
      }
      top = nodes.topLevel();
    }
    String name =
        top.containsKey("name") && top.get("name") instanceof JsonString s
            ? s.value()
            : defaultName;
    List<FieldDefn> fields = new ArrayList<>();
    boolean idAsField = hasStringId && !props.containsKey("id");
    if (idAsField) fields.add(FieldDefn.of("id", FieldType.STRING));
    props.forEach((k, v) -> fields.add(v.toField(k)));
    FeatureType ft =
        new FeatureType(
            name,
            fields,
            List.of(
                new GeomFieldDefn(
                    "",
                    geomType == null ? GeometryType.UNKNOWN : geomType,
                    dim,
                    readCrs(top.getOrDefault("crs", JsonNull.INSTANCE)),
                    true)));
    return new GeoJsonSchema(ft, idAsField, count, extent);
  }

  static Geometry readGeometry(JsonValue g, long featureNumber) {
    try {
      return GeoJsonGeometry.read(g);
    } catch (IllegalArgumentException e) {
      throw new JdalException(
          "Invalid geometry in GeoJSON feature " + featureNumber + ": " + e.getMessage(), e);
    }
  }

  private static SpatialReference readCrs(JsonValue crs) {
    String n = crs.path("properties").path("name").asString("");
    if (!n.isEmpty()) {
      if (n.endsWith("CRS84")) return SpatialReference.WGS84;
      try {
        return SpatialReference.fromUserInput(n);
      } catch (IllegalArgumentException e) {
        // unknown CRS name: fall through to the RFC 7946 default
      }
    }
    return SpatialReference.WGS84;
  }

  // ------------------------------------------------------------------ features

  /**
   * Converts one Feature node. {@code sequence} is the fid to use when the feature has no integer
   * id.
   */
  Feature toFeature(JsonObject node, long sequence) {
    Feature.Builder b = Feature.builder(type);
    JsonValue id = node.path("id");
    b.fid(id instanceof JsonNumber n && n.canConvertToLong() ? n.longValue() : sequence);
    if (idAsField && id instanceof JsonString s) b.set("id", s.value());
    if (node.path("properties") instanceof JsonObject p) {
      for (var e : p.members().entrySet()) {
        if (e.getValue().isNull()) continue;
        if (lenient && type.fieldIndex(e.getKey()).isEmpty()) continue;
        int idx = type.requireFieldIndex(e.getKey());
        b.set(idx, toValue(type.fields().get(idx), e.getValue()));
      }
    }
    if (node.path("geometry") instanceof JsonObject g) b.geometry(readGeometry(g, sequence + 1));
    return b.build();
  }

  private static Object toValue(FieldDefn f, JsonValue v) {
    if (f.subType() == FieldSubType.JSON) return text(v);
    return switch (f.type()) {
      case INTEGER ->
          v instanceof JsonBoolean b ? (b.value() ? 1 : 0) : ((JsonNumber) v).intValue();
      case INTEGER64 -> ((JsonNumber) v).longValue();
      case REAL -> ((JsonNumber) v).doubleValue();
      case STRING -> v instanceof JsonString s ? s.value() : text(v);
      case DATE, TIME, DATETIME -> ((JsonString) v).value();
      case INTEGER_LIST, INTEGER64_LIST, REAL_LIST, STRING_LIST -> {
        List<Object> l = new ArrayList<>();
        for (JsonValue e : (JsonArray) v) {
          l.add(
              e instanceof JsonString s
                  ? s.value()
                  : e instanceof JsonBoolean b
                      ? (Object) (b.value() ? 1 : 0)
                      : e instanceof JsonNumber n ? n.toNumber() : text(e));
        }
        yield l;
      }
      case BINARY -> throw new IllegalStateException("GeoJSON has no binary type");
    };
  }

  private static String text(JsonValue v) {
    try {
      return v.toJson();
    } catch (IllegalArgumentException e) {
      return v.toString(); // e.g. NaN inside a nested value
    }
  }

  /** Accumulates the types of the values seen for one property. */
  private static final class Inferred {
    boolean sawInt,
        sawLong,
        sawReal,
        sawBool,
        sawString,
        sawObject,
        sawDate,
        sawDateTime,
        sawOtherString;
    boolean sawList, listInt, listLong, listReal, listString, listBool, listMixed;

    void add(JsonValue v) {
      switch (v) {
        case JsonNull _ -> {}
        case JsonBoolean _ -> sawBool = true;
        case JsonNumber n -> {
          if (!n.isIntegral()) sawReal = true;
          else if (n.canConvertToInt()) sawInt = true;
          else if (n.canConvertToLong()) sawLong = true;
          else sawReal = true;
        }
        case JsonString s -> {
          sawString = true;
          if (isDate(s.value())) sawDate = true;
          else if (isDateTime(s.value())) sawDateTime = true;
          else sawOtherString = true;
        }
        case JsonArray a -> {
          sawList = true;
          for (JsonValue e : a) {
            switch (e) {
              case JsonBoolean _ -> listBool = true;
              case JsonNumber n -> {
                if (!n.isIntegral()) listReal = true;
                else if (n.canConvertToInt()) listInt = true;
                else if (n.canConvertToLong()) listLong = true;
                else listReal = true;
              }
              case JsonString _ -> listString = true;
              default -> listMixed = true;
            }
          }
        }
        case JsonObject _ -> sawObject = true;
      }
    }

    static boolean isDate(String s) {
      try {
        LocalDate.parse(s);
        return true;
      } catch (DateTimeParseException e) {
        return false;
      }
    }

    static boolean isDateTime(String s) {
      if (s.length() < 16 || s.charAt(10) != 'T') return false;
      try {
        OffsetDateTime.parse(s);
        return true;
      } catch (DateTimeParseException e) {
        try {
          LocalDateTime.parse(s);
          return true;
        } catch (DateTimeParseException e2) {
          return false;
        }
      }
    }

    FieldDefn toField(String name) {
      boolean numeric = sawInt || sawLong || sawReal;
      int kinds =
          (sawBool || numeric ? 1 : 0)
              + (sawString ? 1 : 0)
              + (sawList ? 1 : 0)
              + (sawObject ? 1 : 0);
      boolean listNumeric = listInt || listLong || listReal;
      if (kinds > 1
          || sawObject
          || listMixed
          || (listString && (listNumeric || listBool))
          || (listBool && listNumeric)) {
        return FieldDefn.of(
            name,
            FieldType.STRING,
            (sawObject || sawList) && kinds == 1 ? FieldSubType.JSON : FieldSubType.NONE);
      }
      if (sawBool && !numeric) return FieldDefn.of(name, FieldType.INTEGER, FieldSubType.BOOLEAN);
      if (sawReal) return FieldDefn.of(name, FieldType.REAL);
      if (sawLong) return FieldDefn.of(name, FieldType.INTEGER64);
      if (sawInt || sawBool) return FieldDefn.of(name, FieldType.INTEGER);
      if (sawList) {
        if (listString) return FieldDefn.of(name, FieldType.STRING_LIST);
        if (listReal) return FieldDefn.of(name, FieldType.REAL_LIST);
        if (listLong) return FieldDefn.of(name, FieldType.INTEGER64_LIST);
        if (listBool) return FieldDefn.of(name, FieldType.INTEGER_LIST, FieldSubType.BOOLEAN);
        return FieldDefn.of(name, FieldType.INTEGER_LIST);
      }
      if (sawString) {
        if (!sawOtherString && sawDate && !sawDateTime) return FieldDefn.of(name, FieldType.DATE);
        if (!sawOtherString && sawDateTime && !sawDate)
          return FieldDefn.of(name, FieldType.DATETIME);
      }
      return FieldDefn.of(name, FieldType.STRING);
    }
  }
}
