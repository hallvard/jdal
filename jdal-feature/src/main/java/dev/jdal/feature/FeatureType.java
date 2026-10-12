package dev.jdal.feature;

import dev.jdal.geom.Dimension;
import dev.jdal.geom.GeometryType;
import dev.jdal.srs.SpatialReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * The schema of a layer: a name, attribute fields and geometry fields. Field names match
 * case-insensitively.
 */
public record FeatureType(String name, List<FieldDefn> fields, List<GeomFieldDefn> geomFields) {

  public FeatureType {
    Objects.requireNonNull(name, "name");
    fields = List.copyOf(fields);
    geomFields = List.copyOf(geomFields);
  }

  public static FeatureType of(String name, List<FieldDefn> fields) {
    return new FeatureType(name, fields, List.of());
  }

  /** Schema with one geometry field called "" (GDAL's default for single-geometry formats). */
  public static FeatureType of(
      String name, GeometryType type, SpatialReference srs, List<FieldDefn> fields) {
    List<GeomFieldDefn> g =
        type == GeometryType.NONE
            ? List.of()
            : List.of(new GeomFieldDefn("", type, Dimension.XY, srs, true));
    return new FeatureType(name, fields, g);
  }

  public OptionalInt fieldIndex(String fieldName) {
    for (int i = 0; i < fields.size(); i++) {
      if (fields.get(i).name().equalsIgnoreCase(fieldName)) return OptionalInt.of(i);
    }
    return OptionalInt.empty();
  }

  public int requireFieldIndex(String fieldName) {
    return fieldIndex(fieldName)
        .orElseThrow(() -> new IllegalArgumentException("No field '" + fieldName + "' in " + name));
  }

  public OptionalInt geomFieldIndex(String geomFieldName) {
    for (int i = 0; i < geomFields.size(); i++) {
      if (geomFields.get(i).name().equalsIgnoreCase(geomFieldName)) return OptionalInt.of(i);
    }
    return OptionalInt.empty();
  }

  /** The first geometry field's type, or {@link GeometryType#NONE}. */
  public GeometryType geometryType() {
    return geomFields.isEmpty() ? GeometryType.NONE : geomFields.getFirst().type();
  }

  public SpatialReference srs() {
    return geomFields.isEmpty() ? null : geomFields.getFirst().srs();
  }

  public FeatureType withName(String newName) {
    return new FeatureType(newName, fields, geomFields);
  }

  public FeatureType withFields(List<FieldDefn> newFields) {
    return new FeatureType(name, newFields, geomFields);
  }

  public FeatureType withGeomFields(List<GeomFieldDefn> newGeomFields) {
    return new FeatureType(name, fields, newGeomFields);
  }

  public FeatureType addField(FieldDefn f) {
    var l = new ArrayList<>(fields);
    l.add(f);
    return withFields(l);
  }
}
