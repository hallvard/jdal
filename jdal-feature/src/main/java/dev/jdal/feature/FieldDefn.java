package dev.jdal.feature;

import java.util.Objects;

/**
 * Definition of an attribute field.
 *
 * @param width maximum width or 0 if unknown
 * @param precision digits after the decimal point or 0
 * @param defaultValue default as SQL literal text, or null
 */
public record FieldDefn(
    String name,
    FieldType type,
    FieldSubType subType,
    int width,
    int precision,
    boolean nullable,
    boolean unique,
    String defaultValue,
    String alternativeName,
    String comment) {

  public FieldDefn {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(subType, "subType");
  }

  public static FieldDefn of(String name, FieldType type) {
    return new FieldDefn(name, type, FieldSubType.NONE, 0, 0, true, false, null, null, null);
  }

  public static FieldDefn of(String name, FieldType type, FieldSubType subType) {
    return new FieldDefn(name, type, subType, 0, 0, true, false, null, null, null);
  }

  public FieldDefn withName(String newName) {
    return new FieldDefn(
        newName,
        type,
        subType,
        width,
        precision,
        nullable,
        unique,
        defaultValue,
        alternativeName,
        comment);
  }

  public FieldDefn withType(FieldType newType, FieldSubType newSubType) {
    return new FieldDefn(
        name,
        newType,
        newSubType,
        width,
        precision,
        nullable,
        unique,
        defaultValue,
        alternativeName,
        comment);
  }

  public FieldDefn withWidth(int newWidth, int newPrecision) {
    return new FieldDefn(
        name,
        type,
        subType,
        newWidth,
        newPrecision,
        nullable,
        unique,
        defaultValue,
        alternativeName,
        comment);
  }

  public FieldDefn withNullable(boolean newNullable) {
    return new FieldDefn(
        name,
        type,
        subType,
        width,
        precision,
        newNullable,
        unique,
        defaultValue,
        alternativeName,
        comment);
  }

  public FieldDefn withDefault(String newDefault) {
    return new FieldDefn(
        name,
        type,
        subType,
        width,
        precision,
        nullable,
        unique,
        newDefault,
        alternativeName,
        comment);
  }

  public FieldDefn withUnique(boolean newUnique) {
    return new FieldDefn(
        name,
        type,
        subType,
        width,
        precision,
        nullable,
        newUnique,
        defaultValue,
        alternativeName,
        comment);
  }
}
