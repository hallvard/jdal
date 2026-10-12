package dev.jdal.geom;

import java.util.function.UnaryOperator;

/** A point. Absent Z/M ordinates are NaN; the empty point has NaN X and Y. */
public record Point(Dimension dimension, double x, double y, double z, double m)
    implements Geometry {

  public static Point xy(double x, double y) {
    return new Point(Dimension.XY, x, y, Double.NaN, Double.NaN);
  }

  public static Point xyz(double x, double y, double z) {
    return new Point(Dimension.XYZ, x, y, z, Double.NaN);
  }

  public static Point xym(double x, double y, double m) {
    return new Point(Dimension.XYM, x, y, Double.NaN, m);
  }

  public static Point xyzm(double x, double y, double z, double m) {
    return new Point(Dimension.XYZM, x, y, z, m);
  }

  public static Point empty(Dimension dimension) {
    return new Point(dimension, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
  }

  @Override
  public GeometryType type() {
    return GeometryType.POINT;
  }

  @Override
  public boolean isEmpty() {
    return Double.isNaN(x) && Double.isNaN(y);
  }

  @Override
  public Envelope envelope() {
    return isEmpty() ? Envelope.EMPTY : new Envelope(x, y, x, y);
  }

  @Override
  public Point transform(UnaryOperator<double[]> f) {
    if (isEmpty()) return this;
    double[] c = new double[dimension.size()];
    c[0] = x;
    c[1] = y;
    int k = 2;
    if (dimension.hasZ()) c[k++] = z;
    if (dimension.hasM()) c[k] = m;
    double[] r = f.apply(c);
    k = 2;
    return new Point(
        dimension,
        r[0],
        r[1],
        dimension.hasZ() ? r[k++] : Double.NaN,
        dimension.hasM() ? r[k] : Double.NaN);
  }

  @Override
  public Point withDimension(Dimension target, double defaultZ) {
    if (target == dimension) return this;
    if (isEmpty()) return empty(target);
    return new Point(
        target,
        x,
        y,
        target.hasZ() ? (dimension.hasZ() ? z : defaultZ) : Double.NaN,
        target.hasM() ? (dimension.hasM() ? m : 0.0) : Double.NaN);
  }
}
