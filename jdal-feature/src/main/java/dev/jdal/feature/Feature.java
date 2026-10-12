package dev.jdal.feature;

import dev.jdal.geom.Geometry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable feature: a schema, a feature id, one value per attribute field (null = no value) and
 * one geometry per geometry field (null = none). Binary values are not copied; do not mutate them.
 */
public record Feature(FeatureType type, long fid, List<Object> values, List<Geometry> geometries) {

  public static final long NO_FID = -1;

  public Feature {
    values = Collections.unmodifiableList(new ArrayList<>(values));
    geometries = Collections.unmodifiableList(new ArrayList<>(geometries));
    if (values.size() != type.fields().size()) {
      throw new IllegalArgumentException(
          "Feature has " + values.size() + " values for " + type.fields().size() + " fields");
    }
    if (geometries.size() != type.geomFields().size()) {
      throw new IllegalArgumentException(
          "Feature has "
              + geometries.size()
              + " geometries for "
              + type.geomFields().size()
              + " geometry fields");
    }
  }

  public static Builder builder(FeatureType type) {
    return new Builder(type);
  }

  public Builder toBuilder() {
    Builder b = new Builder(type);
    b.fid = fid;
    for (int i = 0; i < values.size(); i++) b.values.set(i, values.get(i));
    for (int i = 0; i < geometries.size(); i++) b.geoms.set(i, geometries.get(i));
    return b;
  }

  public boolean hasFid() {
    return fid != NO_FID;
  }

  public Object get(int fieldIndex) {
    return values.get(fieldIndex);
  }

  public Object get(String fieldName) {
    return values.get(type.requireFieldIndex(fieldName));
  }

  /** A BOOLEAN-subtype or other integer field as boolean (non-zero is true); null if unset. */
  public Boolean getBoolean(String fieldName) {
    Object v = get(fieldName);
    return v == null ? null : ((Number) v).doubleValue() != 0;
  }

  public String getString(String fieldName) {
    Object v = get(fieldName);
    return v == null ? null : v.toString();
  }

  /** The first geometry or null. */
  public Geometry geometry() {
    return geometries.isEmpty() ? null : geometries.getFirst();
  }

  public Geometry geometry(int geomFieldIndex) {
    return geometries.get(geomFieldIndex);
  }

  public Feature withFid(long newFid) {
    return toBuilder().fid(newFid).build();
  }

  public Feature withGeometry(Geometry g) {
    return toBuilder().geometry(g).build();
  }

  public Feature with(String fieldName, Object value) {
    return toBuilder().set(fieldName, value).build();
  }

  /** Mutable assembly of a feature; values are coerced to the field types. */
  public static final class Builder {
    private final FeatureType type;
    private long fid = NO_FID;
    private final List<Object> values;
    private final List<Geometry> geoms;

    private Builder(FeatureType type) {
      this.type = type;
      this.values = new ArrayList<>(Collections.nCopies(type.fields().size(), null));
      this.geoms = new ArrayList<>(Collections.nCopies(type.geomFields().size(), null));
    }

    public Builder fid(long fid) {
      this.fid = fid;
      return this;
    }

    public Builder set(int index, Object value) {
      values.set(index, FieldValues.coerce(type.fields().get(index), value));
      return this;
    }

    public Builder set(String field, Object value) {
      return set(type.requireFieldIndex(field), value);
    }

    public Builder geometry(Geometry g) {
      return geometry(0, g);
    }

    public Builder geometry(int index, Geometry g) {
      geoms.set(index, g);
      return this;
    }

    public Feature build() {
      return new Feature(type, fid, values, geoms);
    }
  }
}
