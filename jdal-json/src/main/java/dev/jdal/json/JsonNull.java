package dev.jdal.json;

public enum JsonNull implements JsonValue {
  INSTANCE;

  @Override
  public String toString() {
    return "null";
  }
}
