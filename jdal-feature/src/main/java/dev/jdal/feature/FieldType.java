package dev.jdal.feature;

/**
 * Field data types with the Java type used for values: Integer, Long, Double, String, byte[],
 * LocalDate, LocalTime, LocalDateTime or OffsetDateTime, and Lists thereof.
 */
public enum FieldType {
  INTEGER("Integer"),
  INTEGER64("Integer64"),
  REAL("Real"),
  STRING("String"),
  BINARY("Binary"),
  DATE("Date"),
  TIME("Time"),
  DATETIME("DateTime"),
  INTEGER_LIST("IntegerList"),
  INTEGER64_LIST("Integer64List"),
  REAL_LIST("RealList"),
  STRING_LIST("StringList");

  private final String displayName;

  FieldType(String displayName) {
    this.displayName = displayName;
  }

  /** Name as printed by ogrinfo. */
  public String displayName() {
    return displayName;
  }

  public boolean isList() {
    return this == INTEGER_LIST
        || this == INTEGER64_LIST
        || this == REAL_LIST
        || this == STRING_LIST;
  }

  public boolean isNumeric() {
    return this == INTEGER || this == INTEGER64 || this == REAL;
  }
}
