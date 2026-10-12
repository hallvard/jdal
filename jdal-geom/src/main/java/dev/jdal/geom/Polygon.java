package dev.jdal.geom;

import java.util.List;
import java.util.function.UnaryOperator;

/** A polygon: exterior ring first, then interior rings. All rings share {@code dimension}. */
public record Polygon(Dimension dimension, List<CoordinateSequence> rings) implements Geometry {

  public Polygon {
    rings = List.copyOf(rings);
    for (CoordinateSequence r : rings) {
      if (r.dimension() != dimension) {
        throw new IllegalArgumentException(
            "Ring dimension " + r.dimension() + " != polygon " + dimension);
      }
    }
  }

  public static Polygon of(CoordinateSequence exterior, CoordinateSequence... holes) {
    var all = new java.util.ArrayList<CoordinateSequence>();
    Dimension d = exterior.dimension();
    all.add(exterior);
    for (CoordinateSequence h : holes) {
      d = d.union(h.dimension());
    }
    for (CoordinateSequence h : holes) all.add(h);
    final Dimension dim = d;
    return new Polygon(dim, all.stream().map(r -> r.withDimension(dim, 0.0)).toList());
  }

  public static Polygon empty(Dimension dimension) {
    return new Polygon(dimension, List.of());
  }

  public CoordinateSequence exteriorRing() {
    if (rings.isEmpty()) throw new IllegalStateException("empty polygon");
    return rings.getFirst();
  }

  public List<CoordinateSequence> interiorRings() {
    return rings.isEmpty() ? List.of() : rings.subList(1, rings.size());
  }

  @Override
  public GeometryType type() {
    return GeometryType.POLYGON;
  }

  @Override
  public boolean isEmpty() {
    return rings.isEmpty() || rings.getFirst().isEmpty();
  }

  @Override
  public Envelope envelope() {
    return rings.isEmpty() ? Envelope.EMPTY : rings.getFirst().envelope();
  }

  @Override
  public Polygon transform(UnaryOperator<double[]> f) {
    return new Polygon(dimension, rings.stream().map(r -> r.map(f)).toList());
  }

  @Override
  public Polygon withDimension(Dimension target, double defaultZ) {
    return new Polygon(target, rings.stream().map(r -> r.withDimension(target, defaultZ)).toList());
  }
}
