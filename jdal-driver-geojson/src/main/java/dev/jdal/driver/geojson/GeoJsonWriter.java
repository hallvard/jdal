package dev.jdal.driver.geojson;

import dev.jdal.core.JdalException;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldSubType;
import dev.jdal.json.*;
import dev.jdal.srs.SpatialReference;
import dev.jdal.vsi.Vsi;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a FeatureCollection, one feature per line. Output is strict JSON: NaN or infinite
 * attribute values are written as null; NaN or infinite coordinates are an error.
 */
final class GeoJsonWriter {

  private GeoJsonWriter() {}

  static void write(String file, FeatureType type, List<Feature> features) {
    try (Writer w = Vsi.global().newWriter(file)) {
      w.write("{\n\"type\": \"FeatureCollection\",\n\"name\": ");
      w.write(Json.quote(type.name()));
      w.write(",\n");
      SpatialReference srs = type.srs();
      if (srs != null && srs.epsg() > 0 && srs.epsg() != 4326) {
        w.write(
            "\"crs\": { \"type\": \"name\", \"properties\": { \"name\": \"urn:ogc:def:crs:EPSG::"
                + srs.epsg()
                + "\" } },\n");
      }
      w.write("\"features\": [\n");
      for (int i = 0; i < features.size(); i++) {
        Json.writeTo(toJson(features.get(i)), w);
        w.write(i + 1 < features.size() ? ",\n" : "\n");
      }
      w.write("]\n}\n");
    } catch (IOException e) {
      throw new JdalException("Cannot write " + file + ": " + e.getMessage(), e);
    } catch (IllegalArgumentException e) {
      throw new JdalException("Cannot write GeoJSON: " + e.getMessage(), e);
    }
  }

  static JsonObject toJson(Feature f) {
    JsonObject.Builder props = JsonObject.builder();
    List<FieldDefn> fields = f.type().fields();
    for (int i = 0; i < fields.size(); i++) {
      FieldDefn d = fields.get(i);
      Object v = f.get(i);
      props.put(d.name(), v == null ? JsonNull.INSTANCE : value(d, v));
    }
    JsonObject.Builder o = JsonObject.builder().put("type", "Feature");
    if (f.hasFid()) o.put("id", f.fid());
    o.put("properties", props.build());
    o.put(
        "geometry", f.geometry() == null ? JsonNull.INSTANCE : GeoJsonGeometry.write(f.geometry()));
    return o.build();
  }

  private static JsonValue value(FieldDefn d, Object v) {
    if (d.subType() == FieldSubType.BOOLEAN) return booleans(v);
    if (d.subType() == FieldSubType.JSON) {
      try {
        return Json.parse(v.toString());
      } catch (JsonException e) {
        return JsonString.of(v.toString());
      }
    }
    return convert(v);
  }

  private static JsonValue booleans(Object v) {
    if (v instanceof List<?> l) {
      List<JsonValue> out = new ArrayList<>();
      for (Object e : l) out.add(booleans(e));
      return new JsonArray(out);
    }
    return JsonBoolean.of(((Number) v).intValue() != 0);
  }

  private static JsonValue convert(Object v) {
    return switch (v) {
      case byte[] b -> JsonString.of(java.util.HexFormat.of().formatHex(b));
      case Double dv when !Double.isFinite(dv) -> JsonNull.INSTANCE;
      case List<?> l -> new JsonArray(l.stream().map(GeoJsonWriter::convert).toList());
      default -> Json.of(v);
    };
  }
}
