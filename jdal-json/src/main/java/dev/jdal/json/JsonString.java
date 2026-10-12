package dev.jdal.json;

import java.util.Objects;

public record JsonString(String value) implements JsonValue {

  public JsonString {
    Objects.requireNonNull(value, "value");
  }

  public static JsonString of(String s) {
    return new JsonString(s);
  }

  @Override
  public String toString() {
    return Json.write(this);
  }
}
