package dev.jdal.json;

/**
 * An immutable JSON value. The shape (sealed interface of records) follows the JDK's proposed
 * {@code java.util.json} (JEP 540) so a later switch is mechanical.
 */
public sealed interface JsonValue
    permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {

  default boolean isNull() {
    return this == JsonNull.INSTANCE;
  }

  /**
   * Member {@code name} of an object; {@link JsonNull} if this is no object or has no such member.
   */
  default JsonValue path(String name) {
    return JsonNull.INSTANCE;
  }

  /**
   * Element {@code index} of an array; {@link JsonNull} if this is no array or the index is out of
   * range.
   */
  default JsonValue path(int index) {
    return JsonNull.INSTANCE;
  }

  /** The text if this is a string, else {@code fallback}. */
  default String asString(String fallback) {
    return this instanceof JsonString s ? s.value() : fallback;
  }

  /** Compact, strictly valid JSON text. */
  default String toJson() {
    return Json.write(this);
  }
}
