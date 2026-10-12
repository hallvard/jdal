package dev.jdal.driver.geojson;

import dev.jdal.core.JdalException;
import dev.jdal.json.*;
import java.io.Reader;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Streams the Feature objects of a GeoJSON document one at a time. Handles FeatureCollection (the
 * {@code features} array is never held in memory), a single Feature and a bare geometry. The other
 * top level members (type, name, crs, ...) are available from {@link #topLevel()} once the iterator
 * is exhausted.
 */
final class FeatureNodes implements Iterator<JsonObject>, AutoCloseable {

  private enum State {
    INIT,
    IN_FEATURES,
    DONE
  }

  private final JsonReader reader;
  private final Map<String, JsonValue> top = new LinkedHashMap<>();
  private State state = State.INIT;
  private boolean sawFeatures;
  private JsonObject pending;

  FeatureNodes(Reader in) {
    this.reader = new JsonReader(in);
  }

  Map<String, JsonValue> topLevel() {
    return top;
  }

  @Override
  public boolean hasNext() {
    if (pending == null) pending = fetch();
    return pending != null;
  }

  @Override
  public JsonObject next() {
    if (!hasNext()) throw new NoSuchElementException();
    JsonObject n = pending;
    pending = null;
    return n;
  }

  private JsonObject fetch() {
    try {
      switch (state) {
        case INIT -> {
          if (reader.peek() != JsonReader.Token.BEGIN_OBJECT)
            throw new JdalException("Not a GeoJSON document");
          reader.beginObject();
          while (reader.hasNext()) {
            String name = reader.nextName();
            if (name.equals("features") && reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              reader.beginArray();
              sawFeatures = true;
              state = State.IN_FEATURES;
              return fetch();
            }
            top.put(name, reader.nextValue());
          }
          reader.endObject();
          state = State.DONE;
          return single();
        }
        case IN_FEATURES -> {
          while (reader.hasNext()) {
            JsonValue v = reader.nextValue();
            if (v instanceof JsonObject o) return o;
            if (!v.isNull()) throw new JdalException("GeoJSON features must be objects");
          }
          reader.endArray();
          while (reader.hasNext()) {
            String name = reader.nextName();
            top.put(name, reader.nextValue());
          }
          reader.endObject();
          state = State.DONE;
          String type = top.getOrDefault("type", JsonNull.INSTANCE).asString("");
          if (!type.isEmpty() && !type.equals("FeatureCollection")) {
            throw new JdalException("Not a GeoJSON FeatureCollection: type '" + type + "'");
          }
          return null;
        }
        default -> {
          return null;
        }
      }
    } catch (JsonException e) {
      throw new JdalException("Invalid GeoJSON: " + e.getMessage(), e);
    }
  }

  /** The document was a bare Feature or geometry: yield it as one feature. */
  private JsonObject single() {
    String type = top.getOrDefault("type", JsonNull.INSTANCE).asString("");
    switch (type) {
      case "Feature":
        return new JsonObject(top);
      case "Point",
      "LineString",
      "Polygon",
      "MultiPoint",
      "MultiLineString",
      "MultiPolygon",
      "GeometryCollection":
        return JsonObject.builder()
            .put("type", "Feature")
            .put("geometry", new JsonObject(top))
            .put("properties", JsonObject.EMPTY)
            .build();
      case "FeatureCollection":
        return null; // empty collection without features member
      default:
        if (sawFeatures) return null;
        throw new JdalException("Not a GeoJSON document: unknown type '" + type + "'");
    }
  }

  @Override
  public void close() {
    reader.close();
  }
}
