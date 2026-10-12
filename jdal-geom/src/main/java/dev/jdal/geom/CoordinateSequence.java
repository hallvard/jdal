package dev.jdal.geom;

import java.util.Arrays;

/**
 * Immutable sequence of coordinates stored interleaved in one {@code double[]}. Ordinate order is
 * X, Y, [Z], [M] according to the {@link Dimension}.
 */
public final class CoordinateSequence {

  private final Dimension dimension;
  private final double[] data;

  private CoordinateSequence(Dimension dimension, double[] data) {
    this.dimension = dimension;
    this.data = data;
  }

  public static CoordinateSequence empty(Dimension dimension) {
    return new CoordinateSequence(dimension, new double[0]);
  }

  /** Copies {@code ordinates}, which must hold a multiple of {@code dimension.size()} values. */
  public static CoordinateSequence of(Dimension dimension, double... ordinates) {
    if (ordinates.length % dimension.size() != 0) {
      throw new IllegalArgumentException(
          ordinates.length
              + " ordinates is not a multiple of "
              + dimension.size()
              + " for "
              + dimension);
    }
    return new CoordinateSequence(dimension, ordinates.clone());
  }

  /** Convenience for 2D sequences. */
  public static CoordinateSequence xy(double... ordinates) {
    return of(Dimension.XY, ordinates);
  }

  /** Takes ownership of the array. Package-private for codecs. */
  static CoordinateSequence wrap(Dimension dimension, double[] data) {
    return new CoordinateSequence(dimension, data);
  }

  public Dimension dimension() {
    return dimension;
  }

  public int size() {
    return data.length / dimension.size();
  }

  public boolean isEmpty() {
    return data.length == 0;
  }

  public double x(int i) {
    return data[i * dimension.size()];
  }

  public double y(int i) {
    return data[i * dimension.size() + 1];
  }

  /** Z of coordinate {@code i}, or NaN if there is no Z. */
  public double z(int i) {
    return dimension.hasZ() ? data[i * dimension.size() + 2] : Double.NaN;
  }

  /** M of coordinate {@code i}, or NaN if there is no M. */
  public double m(int i) {
    return dimension.hasM() ? data[i * dimension.size() + (dimension.hasZ() ? 3 : 2)] : Double.NaN;
  }

  public boolean isClosed() {
    int n = size();
    return n > 0
        && x(0) == x(n - 1)
        && y(0) == y(n - 1)
        && (!dimension.hasZ() || Double.compare(z(0), z(n - 1)) == 0);
  }

  public Envelope envelope() {
    double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
    for (int i = 0, n = size(); i < n; i++) {
      double x = x(i), y = y(i);
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
    return new Envelope(minX, minY, maxX, maxY);
  }

  /** A copy of the raw interleaved ordinates. */
  public double[] toArray() {
    return data.clone();
  }

  /**
   * Applies {@code f} to each coordinate; {@code f} receives and returns a fresh {@code
   * double[size]}.
   */
  public CoordinateSequence map(java.util.function.UnaryOperator<double[]> f) {
    int s = dimension.size();
    double[] out = new double[data.length];
    double[] buf = new double[s];
    for (int i = 0; i < data.length; i += s) {
      System.arraycopy(data, i, buf, 0, s);
      double[] r = f.apply(buf);
      System.arraycopy(r, 0, out, i, s);
    }
    return new CoordinateSequence(dimension, out);
  }

  /**
   * Re-expresses the sequence in {@code target}: missing Z becomes {@code defaultZ}, missing M
   * becomes 0.
   */
  public CoordinateSequence withDimension(Dimension target, double defaultZ) {
    if (target == dimension) return this;
    int n = size();
    double[] out = new double[n * target.size()];
    for (int i = 0; i < n; i++) {
      int o = i * target.size();
      out[o] = x(i);
      out[o + 1] = y(i);
      int k = o + 2;
      if (target.hasZ()) out[k++] = dimension.hasZ() ? z(i) : defaultZ;
      if (target.hasM()) out[k] = dimension.hasM() ? m(i) : 0.0;
    }
    return new CoordinateSequence(target, out);
  }

  public CoordinateSequence reversed() {
    int s = dimension.size(), n = size();
    double[] out = new double[data.length];
    for (int i = 0; i < n; i++) {
      System.arraycopy(data, i * s, out, (n - 1 - i) * s, s);
    }
    return new CoordinateSequence(dimension, out);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof CoordinateSequence c
        && dimension == c.dimension
        && Arrays.equals(data, c.data);
  }

  @Override
  public int hashCode() {
    return 31 * dimension.hashCode() + Arrays.hashCode(data);
  }

  @Override
  public String toString() {
    return Wkt.formatCoordinates(this);
  }
}
