package dev.jdal.feature;

import dev.jdal.geom.Dimension;
import dev.jdal.geom.GeometryType;
import dev.jdal.srs.SpatialReference;
import java.util.Objects;

/**
 * Definition of a geometry column.
 *
 * @param type declared geometry type, {@link GeometryType#UNKNOWN} for "any"
 * @param dimension declared ordinates
 * @param srs spatial reference or null if unknown
 */
public record GeomFieldDefn(
    String name, GeometryType type, Dimension dimension, SpatialReference srs, boolean nullable) {

  public GeomFieldDefn {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(dimension, "dimension");
  }

  public static GeomFieldDefn of(String name, GeometryType type, SpatialReference srs) {
    return new GeomFieldDefn(name, type, Dimension.XY, srs, true);
  }

  public GeomFieldDefn withType(GeometryType newType, Dimension newDimension) {
    return new GeomFieldDefn(name, newType, newDimension, srs, nullable);
  }

  public GeomFieldDefn withSrs(SpatialReference newSrs) {
    return new GeomFieldDefn(name, type, dimension, newSrs, nullable);
  }

  public GeomFieldDefn withName(String newName) {
    return new GeomFieldDefn(newName, type, dimension, srs, nullable);
  }
}
