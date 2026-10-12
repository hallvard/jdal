package dev.jdal.core;

import dev.jdal.geom.Geometry;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Predicate;

/**
 * Exact geometry tests, provided by the {@code jdal-ops} module (found with {@link ServiceLoader},
 * so that the core does not depend on JTS).
 */
public interface GeometryOps {

  /** A test of a feature geometry against {@code filter}; the filter is prepared once. */
  Predicate<Geometry> prepare(SpatialOp op, Geometry filter);

  /** The implementation on the class path, if any. */
  static Optional<GeometryOps> find() {
    return Holder.OPS;
  }

  final class Holder {
    private Holder() {}

    static final Optional<GeometryOps> OPS = ServiceLoader.load(GeometryOps.class).findFirst();
  }
}
