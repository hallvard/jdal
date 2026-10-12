package dev.jdal.geom;

import java.util.List;
import java.util.function.UnaryOperator;

public record MultiPolygon(Dimension dimension, List<Polygon> parts) implements Geometry {

  public MultiPolygon {
    parts = List.copyOf(parts);
    for (Polygon p : parts) {
      if (p.dimension() != dimension) {
        throw new IllegalArgumentException("Part dimension " + p.dimension() + " != " + dimension);
      }
    }
  }

  /** Builds a collection whose dimension is the union of the parts' dimensions. */
  public static MultiPolygon of(List<Polygon> parts) {
    return of(Dimension.XY, parts);
  }

  /** Like {@link #of(List)} but never narrower than {@code minimum}. */
  public static MultiPolygon of(Dimension minimum, List<Polygon> parts) {
    Dimension d = minimum;
    for (Polygon p : parts) d = d.union(p.dimension());
    final Dimension dim = d;
    return new MultiPolygon(dim, parts.stream().map(p -> p.withDimension(dim, 0.0)).toList());
  }

  public static MultiPolygon empty(Dimension dimension) {
    return new MultiPolygon(dimension, List.of());
  }

  @Override
  public GeometryType type() {
    return GeometryType.MULTIPOLYGON;
  }

  @Override
  public boolean isEmpty() {
    return parts.stream().allMatch(Geometry::isEmpty);
  }

  @Override
  public Envelope envelope() {
    Envelope e = Envelope.EMPTY;
    for (Polygon p : parts) e = e.union(p.envelope());
    return e;
  }

  @Override
  public MultiPolygon transform(UnaryOperator<double[]> f) {
    return new MultiPolygon(dimension, parts.stream().map(p -> p.transform(f)).toList());
  }

  @Override
  public MultiPolygon withDimension(Dimension target, double defaultZ) {
    return new MultiPolygon(
        target, parts.stream().map(p -> p.withDimension(target, defaultZ)).toList());
  }
}
