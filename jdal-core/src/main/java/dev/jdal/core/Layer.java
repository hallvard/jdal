package dev.jdal.core;

import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.feature.FieldDefn;
import dev.jdal.geom.Envelope;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A collection of features sharing a {@link FeatureType}. Streams must be closed (use
 * try-with-resources) as they may hold database cursors or file handles. A layer is not thread safe
 * unless a driver says so.
 */
public interface Layer extends AutoCloseable {

  String name();

  FeatureType type();

  Set<Capability> capabilities();

  default boolean can(Capability c) {
    return capabilities().contains(c);
  }

  /** All features in the layer's natural order. */
  Stream<Feature> features();

  /**
   * Features matching {@code query}. The default scans {@link #features()}; drivers override this
   * to push spatial and attribute filters down to indexes or the database.
   */
  default Stream<Feature> features(Query query) {
    if (query.isUnrestricted()) return features();
    Predicate<Feature> sql =
        query.where() == null ? null : SqlEngine.require().compileFilter(type(), query.where());
    Stream<Feature> s = features();
    if (query.bbox() != null || query.filter() != null || query.spatial() != null || sql != null) {
      s = s.filter(f -> query.matches(f) && (sql == null || sql.test(f)));
    }
    if (query.limit() >= 0) s = s.limit(query.limit());
    return s;
  }

  default long count(Query query) {
    try (Stream<Feature> s = features(query)) {
      return s.count();
    }
  }

  default long count() {
    return count(Query.ALL);
  }

  /** Bounding box of all geometries of the first geometry field. */
  default Optional<Envelope> extent() {
    Envelope e = Envelope.EMPTY;
    try (Stream<Feature> s = features()) {
      for (var it = s.iterator(); it.hasNext(); ) {
        var g = it.next().geometry();
        if (g != null) e = e.union(g.envelope());
      }
    }
    return e.isEmpty() ? Optional.empty() : Optional.of(e);
  }

  default Optional<Feature> feature(long fid) {
    try (Stream<Feature> s = features()) {
      return s.filter(f -> f.fid() == fid).findFirst();
    }
  }

  // -------------------------------------------------------------------- writing

  /**
   * Adds a feature (whose type must equal {@link #type()}) and returns it with the assigned fid.
   */
  default Feature insert(Feature feature) {
    throw new UnsupportedOperationException(name() + " is read-only");
  }

  /** Replaces the feature with the same fid. */
  default void update(Feature feature) {
    throw new UnsupportedOperationException(name() + " does not support updates");
  }

  /**
   * @return true if a feature was removed
   */
  default boolean delete(long fid) {
    throw new UnsupportedOperationException(name() + " does not support deletes");
  }

  default void addField(FieldDefn field) {
    throw new UnsupportedOperationException(name() + " does not support schema changes");
  }

  /** Removes an attribute field. */
  default void deleteField(String field) {
    throw new UnsupportedOperationException(name() + " does not support deleting fields");
  }

  /** Replaces the definition of {@code field} (rename and/or type change). */
  default void alterField(String field, FieldDefn newDefinition) {
    throw new UnsupportedOperationException(name() + " does not support altering fields");
  }

  default void rename(String newName) {
    throw new UnsupportedOperationException(name() + " does not support renaming");
  }

  /** Creates an attribute index on {@code field} (only some drivers support them). */
  default void createAttributeIndex(String field) {
    throw new UnsupportedOperationException("CREATE INDEX ON not supported by this driver.");
  }

  /** Drops the attribute index of {@code field}, or all indexes if {@code field} is null. */
  default void dropAttributeIndex(String field) {
    throw new UnsupportedOperationException("Indexes not supported by this driver.");
  }

  /** Flushes pending writes. */
  default void sync() {}

  @Override
  default void close() {}
}
