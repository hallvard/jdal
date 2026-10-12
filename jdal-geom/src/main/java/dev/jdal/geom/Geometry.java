package dev.jdal.geom;

import java.util.function.UnaryOperator;

/**
 * An immutable OGC simple-features geometry. Curved and surface types (circular strings, polyhedral
 * surfaces, ...) are not yet modelled.
 */
public sealed interface Geometry
    permits Point,
        LineString,
        Polygon,
        MultiPoint,
        MultiLineString,
        MultiPolygon,
        GeometryCollection {

  GeometryType type();

  Dimension dimension();

  boolean isEmpty();

  /** 2D bounding box; {@link Envelope#EMPTY} for empty geometries. */
  Envelope envelope();

  /**
   * Applies {@code f} to every coordinate. {@code f} gets a scratch {@code
   * double[dimension.size()]} (X, Y, [Z], [M]) and returns the new ordinates in an array of the
   * same length.
   */
  Geometry transform(UnaryOperator<double[]> f);

  /**
   * Re-expresses this geometry with the ordinates of {@code target}; added Z becomes {@code
   * defaultZ}, added M 0.
   */
  Geometry withDimension(Dimension target, double defaultZ);

  default Geometry withDimension(Dimension target) {
    return withDimension(target, 0.0);
  }

  /** Swaps X and Y of all coordinates. */
  default Geometry swapXY() {
    return transform(
        c -> {
          double t = c[0];
          c[0] = c[1];
          c[1] = t;
          return c;
        });
  }

  default String toWkt() {
    return Wkt.write(this);
  }

  default byte[] toWkb() {
    return Wkb.write(this);
  }

  /** ISO WKB type code including the dimension offset, e.g. 1002 for LineString Z. */
  default int isoTypeCode() {
    return type().code() + dimension().isoOffset();
  }
}
