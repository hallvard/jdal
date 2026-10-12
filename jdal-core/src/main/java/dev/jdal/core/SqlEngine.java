package dev.jdal.core;

import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.geom.Geometry;
import java.util.ServiceLoader;
import java.util.function.Predicate;

/**
 * The OGR SQL engine, provided by the {@code jdal-sql} module through {@link ServiceLoader}. It
 * lives behind this interface because the SQL module depends on this one.
 */
public interface SqlEngine {

  /**
   * Compiles an attribute filter ({@code WHERE} syntax of OGR SQL) for features of {@code type}.
   *
   * @throws JdalException if the expression is invalid for the type
   */
  Predicate<Feature> compileFilter(FeatureType type, String where);

  /**
   * Executes a statement against {@code dataset}: SELECT (result layer), or DDL such as ALTER TABLE
   * (null result).
   *
   * @param spatialFilter restricts the result to features whose geometry envelope intersects; may
   *     be null
   */
  Layer execute(Dataset dataset, String sql, Geometry spatialFilter);

  /** The installed engine; fails if {@code jdal-sql} is not on the class path. */
  static SqlEngine require() {
    return Holder.ENGINE.orElseThrow(
        () -> new JdalException("The OGR SQL engine (jdal-sql) is not available"));
  }

  final class Holder {
    static final java.util.Optional<SqlEngine> ENGINE =
        ServiceLoader.load(SqlEngine.class).findFirst();

    private Holder() {}
  }
}
