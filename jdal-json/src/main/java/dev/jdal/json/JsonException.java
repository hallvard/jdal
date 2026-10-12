package dev.jdal.json;

/** Malformed JSON input. The message includes line and column. */
public class JsonException extends RuntimeException {

  public JsonException(String message) {
    super(message);
  }
}
