package dev.jdal.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public record JsonArray(List<JsonValue> elements) implements JsonValue, Iterable<JsonValue> {

  public JsonArray {
    elements = Collections.unmodifiableList(new ArrayList<>(elements));
  }

  public static JsonArray of(JsonValue... values) {
    return new JsonArray(List.of(values));
  }

  public int size() {
    return elements.size();
  }

  public JsonValue get(int i) {
    return elements.get(i);
  }

  @Override
  public JsonValue path(int index) {
    return index >= 0 && index < elements.size() ? elements.get(index) : JsonNull.INSTANCE;
  }

  @Override
  public Iterator<JsonValue> iterator() {
    return elements.iterator();
  }

  @Override
  public String toString() {
    return Json.write(this);
  }
}
