package dev.jdal.gml;

import dev.jdal.geom.Geometry;
import java.util.List;

/**
 * A feature as found in the document: untyped text values and geometries in document order (axes
 * not yet swapped).
 *
 * @param typeName local name of the feature element
 * @param id the {@code gml:id} (or {@code fid}) attribute, or null
 * @param idAttribute the attribute the id came from ({@code gml:id} or {@code fid})
 * @param properties text values by flattened property name; repeated properties appear repeatedly
 * @param geometries the geometry properties
 * @param boundedBySrs {@code srsName} of the feature's bounding box, or null
 */
public record GmlFeature(
    String typeName,
    String id,
    String idAttribute,
    List<Property> properties,
    List<GeometryProperty> geometries,
    String boundedBySrs) {

  public record Property(String name, String text) {}

  public record GeometryProperty(String name, Geometry geometry, String srsName) {}
}
