package dev.jdal.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** An object whose members keep their insertion order. */
public record JsonObject(Map<String, JsonValue> members) implements JsonValue {

  public static final JsonObject EMPTY = new JsonObject(Map.of());

  public JsonObject {
    members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
  }

  public static Builder builder() {
    return new Builder();
  }

  public int size() {
    return members.size();
  }

  public boolean has(String name) {
    return members.containsKey(name);
  }

  /** The member or null. */
  public JsonValue get(String name) {
    return members.get(name);
  }

  @Override
  public JsonValue path(String name) {
    JsonValue v = members.get(name);
    return v == null ? JsonNull.INSTANCE : v;
  }

  @Override
  public String toString() {
    return Json.write(this);
  }

  public static final class Builder {
    private final Map<String, JsonValue> map = new LinkedHashMap<>();

    public Builder put(String name, JsonValue value) {
      map.put(name, value);
      return this;
    }

    /** Converts {@code value} with {@link Json#of(Object)}. */
    public Builder put(String name, Object value) {
      map.put(name, Json.of(value));
      return this;
    }

    public JsonObject build() {
      return new JsonObject(map);
    }
  }
}
