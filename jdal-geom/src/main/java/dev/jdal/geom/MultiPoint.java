package dev.jdal.geom;

import java.util.List;
import java.util.function.UnaryOperator;

public record MultiPoint(Dimension dimension, List<Point> parts) implements Geometry {

  public MultiPoint {
    parts = List.copyOf(parts);
    for (Point p : parts) {
      if (p.dimension() != dimension) {
        throw new IllegalArgumentException("Part dimension " + p.dimension() + " != " + dimension);
      }
    }
  }

  /** Builds a collection whose dimension is the union of the parts' dimensions. */
  public static MultiPoint of(List<Point> parts) {
    return of(Dimension.XY, parts);
  }

  /** Like {@link #of(List)} but never narrower than {@code minimum}. */
  public static MultiPoint of(Dimension minimum, List<Point> parts) {
    Dimension d = minimum;
    for (Point p : parts) d = d.union(p.dimension());
    final Dimension dim = d;
    return new MultiPoint(dim, parts.stream().map(p -> p.withDimension(dim, 0.0)).toList());
  }

  public static MultiPoint empty(Dimension dimension) {
    return new MultiPoint(dimension, List.of());
  }

  @Override
  public GeometryType type() {
    return GeometryType.MULTIPOINT;
  }

  @Override
  public boolean isEmpty() {
    return parts.stream().allMatch(Geometry::isEmpty);
  }

  @Override
  public Envelope envelope() {
    Envelope e = Envelope.EMPTY;
    for (Point p : parts) e = e.union(p.envelope());
    return e;
  }

  @Override
  public MultiPoint transform(UnaryOperator<double[]> f) {
    return new MultiPoint(dimension, parts.stream().map(p -> p.transform(f)).toList());
  }

  @Override
  public MultiPoint withDimension(Dimension target, double defaultZ) {
    return new MultiPoint(
        target, parts.stream().map(p -> p.withDimension(target, defaultZ)).toList());
  }
}
