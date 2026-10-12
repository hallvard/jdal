package dev.jdal.core;

import dev.jdal.geom.Geometry;
import java.util.List;
import java.util.Optional;

/** A set of layers, e.g. a file, a directory or a database connection. */
public interface Dataset extends AutoCloseable {

  String name();

  Driver driver();

  /** The layers in a stable order. */
  List<Layer> layers();

  default Optional<Layer> layer(String name) {
    return layers().stream().filter(l -> l.name().equalsIgnoreCase(name)).findFirst();
  }

  default Layer requireLayer(String name) {
    return layer(name).orElseThrow(() -> new JdalException("No layer '" + name + "' in " + name()));
  }

  default boolean canCreateLayers() {
    return false;
  }

  /**
   * Creates an empty layer from {@code type}'s name, fields and geometry fields.
   *
   * @param options driver specific layer creation options (case-insensitive keys)
   */
  default Layer createLayer(
      dev.jdal.feature.FeatureType type, java.util.Map<String, String> options) {
    throw new UnsupportedOperationException(driver().name() + " cannot create layers");
  }

  default void deleteLayer(String name) {
    throw new UnsupportedOperationException(driver().name() + " cannot delete layers");
  }

  /**
   * Runs a statement with the default dialect; see {@link #executeSql(String, Geometry, String)}.
   */
  default Layer executeSql(String sql) {
    return executeSql(sql, null, null);
  }

  default Layer executeSql(String sql, String dialect) {
    return executeSql(sql, null, dialect);
  }

  /**
   * Runs a SQL statement and returns the result layer, or null for statements without result.
   * Without dialect (or with {@code OGRSQL}) the generic OGR SQL engine is used; drivers with their
   * own SQL (GeoPackage, PostgreSQL) override this to use it unless {@code OGRSQL} is requested.
   *
   * @param spatialFilter restricts the result to features intersecting the geometry's envelope; may
   *     be null
   * @param dialect null, "OGRSQL", or a dialect the driver understands
   */
  default Layer executeSql(String sql, Geometry spatialFilter, String dialect) {
    if (dialect != null && !dialect.isEmpty() && !dialect.equalsIgnoreCase("OGRSQL")) {
      Diagnostics.warn(
          "Dialect '"
              + dialect
              + "' is unsupported. Only supported dialects are 'OGRSQL'. Defaulting to OGRSQL");
    }
    return ogrSql(sql, spatialFilter);
  }

  /** Runs the statement with the generic OGR SQL engine. */
  default Layer ogrSql(String sql, Geometry spatialFilter) {
    return SqlEngine.require().execute(this, sql, spatialFilter);
  }

  @Override
  default void close() {}
}
