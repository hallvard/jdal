package dev.jdal.geom;

/** Immutable 2D bounding box. The empty envelope has {@code minX > maxX}. */
public record Envelope(double minX, double minY, double maxX, double maxY) {

  public static final Envelope EMPTY =
      new Envelope(
          Double.POSITIVE_INFINITY,
          Double.POSITIVE_INFINITY,
          Double.NEGATIVE_INFINITY,
          Double.NEGATIVE_INFINITY);

  public boolean isEmpty() {
    return minX > maxX || minY > maxY;
  }

  public double width() {
    return isEmpty() ? 0 : maxX - minX;
  }

  public double height() {
    return isEmpty() ? 0 : maxY - minY;
  }

  public boolean intersects(Envelope o) {
    return !isEmpty()
        && !o.isEmpty()
        && o.minX <= maxX
        && o.maxX >= minX
        && o.minY <= maxY
        && o.maxY >= minY;
  }

  public boolean contains(double x, double y) {
    return x >= minX && x <= maxX && y >= minY && y <= maxY;
  }

  public Envelope expandToInclude(double x, double y) {
    if (Double.isNaN(x) || Double.isNaN(y)) return this;
    return new Envelope(Math.min(minX, x), Math.min(minY, y), Math.max(maxX, x), Math.max(maxY, y));
  }

  public Envelope union(Envelope o) {
    if (o.isEmpty()) return this;
    if (isEmpty()) return o;
    return new Envelope(
        Math.min(minX, o.minX),
        Math.min(minY, o.minY),
        Math.max(maxX, o.maxX),
        Math.max(maxY, o.maxY));
  }
}
