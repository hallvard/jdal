package dev.jdal.geom;

import java.util.function.UnaryOperator;

public record LineString(CoordinateSequence coordinates) implements Geometry {

  public static LineString of(double... xy) {
    return new LineString(CoordinateSequence.xy(xy));
  }

  @Override
  public GeometryType type() {
    return GeometryType.LINESTRING;
  }

  @Override
  public Dimension dimension() {
    return coordinates.dimension();
  }

  @Override
  public boolean isEmpty() {
    return coordinates.isEmpty();
  }

  @Override
  public Envelope envelope() {
    return coordinates.envelope();
  }

  public boolean isClosed() {
    return coordinates.isClosed();
  }

  @Override
  public LineString transform(UnaryOperator<double[]> f) {
    return new LineString(coordinates.map(f));
  }

  @Override
  public LineString withDimension(Dimension target, double defaultZ) {
    return new LineString(coordinates.withDimension(target, defaultZ));
  }
}
