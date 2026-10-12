package dev.jdal.geom;

import java.util.List;
import java.util.function.UnaryOperator;

public record MultiLineString(Dimension dimension, List<LineString> parts) implements Geometry {

  public MultiLineString {
    parts = List.copyOf(parts);
    for (LineString p : parts) {
      if (p.dimension() != dimension) {
        throw new IllegalArgumentException("Part dimension " + p.dimension() + " != " + dimension);
      }
    }
  }

  /** Builds a collection whose dimension is the union of the parts' dimensions. */
  public static MultiLineString of(List<LineString> parts) {
    return of(Dimension.XY, parts);
  }

  /** Like {@link #of(List)} but never narrower than {@code minimum}. */
  public static MultiLineString of(Dimension minimum, List<LineString> parts) {
    Dimension d = minimum;
    for (LineString p : parts) d = d.union(p.dimension());
    final Dimension dim = d;
    return new MultiLineString(dim, parts.stream().map(p -> p.withDimension(dim, 0.0)).toList());
  }

  public static MultiLineString empty(Dimension dimension) {
    return new MultiLineString(dimension, List.of());
  }

  @Override
  public GeometryType type() {
    return GeometryType.MULTILINESTRING;
  }

  @Override
  public boolean isEmpty() {
    return parts.stream().allMatch(Geometry::isEmpty);
  }

  @Override
  public Envelope envelope() {
    Envelope e = Envelope.EMPTY;
    for (LineString p : parts) e = e.union(p.envelope());
    return e;
  }

  @Override
  public MultiLineString transform(UnaryOperator<double[]> f) {
    return new MultiLineString(dimension, parts.stream().map(p -> p.transform(f)).toList());
  }

  @Override
  public MultiLineString withDimension(Dimension target, double defaultZ) {
    return new MultiLineString(
        target, parts.stream().map(p -> p.withDimension(target, defaultZ)).toList());
  }
}
