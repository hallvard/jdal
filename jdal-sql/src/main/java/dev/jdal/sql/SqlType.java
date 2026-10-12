package dev.jdal.sql;

/** Types of the SQL engine's expressions (GDAL's {@code swq_field_type}). */
enum SqlType {
  INTEGER,
  INTEGER64,
  FLOAT,
  STRING,
  BOOLEAN,
  DATE,
  TIME,
  TIMESTAMP,
  GEOMETRY,
  OTHER,
  ERROR;

  boolean isInteger() {
    return this == INTEGER || this == INTEGER64;
  }

  boolean isDateLike() {
    return this == DATE || this == TIME || this == TIMESTAMP;
  }

  String displayName() {
    return switch (this) {
      case INTEGER -> "integer";
      case INTEGER64 -> "bigint";
      case FLOAT -> "float";
      case STRING -> "string";
      case BOOLEAN -> "boolean";
      case DATE -> "date";
      case TIME -> "time";
      case TIMESTAMP -> "timestamp";
      case GEOMETRY -> "geometry";
      default -> "unknown";
    };
  }
}
