package dev.jdal.geom;

import java.util.List;
import java.util.function.UnaryOperator;

public record GeometryCollection(Dimension dimension, List<Geometry> parts) implements Geometry {

  public GeometryCollection {
    parts = List.copyOf(parts);
    for (Geometry p : parts) {
      if (p.dimension() != dimension) {
        throw new IllegalArgumentException("Part dimension " + p.dimension() + " != " + dimension);
      }
    }
  }

  /** Builds a collection whose dimension is the union of the parts' dimensions. */
  public static GeometryCollection of(List<Geometry> parts) {
    return of(Dimension.XY, parts);
  }

  /** Like {@link #of(List)} but never narrower than {@code minimum}. */
  public static GeometryCollection of(Dimension minimum, List<Geometry> parts) {
    Dimension d = minimum;
    for (Geometry p : parts) d = d.union(p.dimension());
    final Dimension dim = d;
    return new GeometryCollection(dim, parts.stream().map(p -> p.withDimension(dim, 0.0)).toList());
  }

  public static GeometryCollection empty(Dimension dimension) {
    return new GeometryCollection(dimension, List.of());
  }

  @Override
  public GeometryType type() {
    return GeometryType.GEOMETRYCOLLECTION;
  }

  @Override
  public boolean isEmpty() {
    return parts.stream().allMatch(Geometry::isEmpty);
  }

  @Override
  public Envelope envelope() {
    Envelope e = Envelope.EMPTY;
    for (Geometry p : parts) e = e.union(p.envelope());
    return e;
  }

  @Override
  public GeometryCollection transform(UnaryOperator<double[]> f) {
    return new GeometryCollection(dimension, parts.stream().map(p -> p.transform(f)).toList());
  }

  @Override
  public GeometryCollection withDimension(Dimension target, double defaultZ) {
    return new GeometryCollection(
        target, parts.stream().map(p -> p.withDimension(target, defaultZ)).toList());
  }
}
