package dev.jdal.srs;

import dev.jdal.geom.Geometry;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/** Transforms coordinates and geometries between two spatial references. Not thread safe. */
public final class CoordinateTransformation {

  private static final CoordinateTransformFactory FACTORY = new CoordinateTransformFactory();

  private final CoordinateTransform transform;
  private final boolean identity;
  private final ProjCoordinate in = new ProjCoordinate();
  private final ProjCoordinate out = new ProjCoordinate();

  private CoordinateTransformation(CoordinateTransform transform, boolean identity) {
    this.transform = transform;
    this.identity = identity;
  }

  public static CoordinateTransformation between(SpatialReference source, SpatialReference target) {
    if (source.isSame(target)) return new CoordinateTransformation(null, true);
    return new CoordinateTransformation(
        FACTORY.createTransform(source.toProj(), target.toProj()), false);
  }

  /** Transforms {@code xyz} in place (Z is passed through unchanged). */
  public void transform(double[] xyz) {
    if (identity) return;
    in.x = xyz[0];
    in.y = xyz[1];
    try {
      transform.transform(in, out);
    } catch (RuntimeException e) {
      throw new TransformException(
          "Cannot transform (" + xyz[0] + " " + xyz[1] + "): " + e.getMessage(), e);
    }
    xyz[0] = out.x;
    xyz[1] = out.y;
  }

  public Geometry transform(Geometry g) {
    if (identity) return g;
    return g.transform(
        c -> {
          transform(c);
          return c;
        });
  }
}
