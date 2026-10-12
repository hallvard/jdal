package dev.jdal.feature;

/**
 * Refinement of a {@link FieldType}. BOOLEAN fields are INTEGER (or INTEGER_LIST) fields holding 0
 * and 1.
 */
public enum FieldSubType {
  NONE("None"),
  BOOLEAN("Boolean"),
  INT16("Int16"),
  FLOAT32("Float32"),
  JSON("JSON"),
  UUID("UUID");

  private final String displayName;

  FieldSubType(String displayName) {
    this.displayName = displayName;
  }

  public String displayName() {
    return displayName;
  }
}
