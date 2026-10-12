package dev.jdal.core;

import dev.jdal.geom.Envelope;
import dev.jdal.geom.Geometry;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A relation to a geometry, such as "intersects this polygon". The geometry is in the CRS of the
 * layer that is queried. Without the {@code jdal-ops} module only {@code INTERSECTS} is available,
 * and it compares envelopes.
 */
public final class SpatialFilter {

  private final SpatialOp op;
  private final Geometry geometry;
  private volatile Predicate<Geometry> test;

  public SpatialFilter(SpatialOp op, Geometry geometry) {
    this.op = Objects.requireNonNull(op);
    this.geometry = Objects.requireNonNull(geometry);
  }

  public SpatialOp op() {
    return op;
  }

  public Geometry geometry() {
    return geometry;
  }

  public Envelope envelope() {
    return geometry.envelope();
  }

  /** Tests a feature geometry (null never matches). */
  public boolean test(Geometry g) {
    if (g == null) return false;
    Predicate<Geometry> t = test;
    if (t == null) {
      t = prepare();
      test = t;
    }
    return t.test(g);
  }

  private Predicate<Geometry> prepare() {
    return GeometryOps.find()
        .map(ops -> ops.prepare(op, geometry))
        .orElseGet(
            () -> {
              if (op != SpatialOp.INTERSECTS) {
                throw new JdalException(
                    "Spatial filter " + op + " needs the jdal-ops module on the class path");
              }
              Envelope e = geometry.envelope();
              return g -> g.envelope().intersects(e);
            });
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SpatialFilter s && s.op == op && s.geometry.equals(geometry);
  }

  @Override
  public int hashCode() {
    return Objects.hash(op, geometry);
  }

  @Override
  public String toString() {
    return op + " " + geometry.toWkt();
  }
}
