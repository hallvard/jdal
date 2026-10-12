package dev.jdal.core;

import dev.jdal.feature.Feature;
import dev.jdal.geom.Envelope;
import dev.jdal.geom.Geometry;
import java.util.function.Predicate;

/**
 * Restricts the features of a layer.
 *
 * @param bbox keep only features whose geometry envelope intersects this box, or null
 * @param filter additional predicate, or null
 * @param limit maximum number of features, or {@code -1} for no limit
 * @param spatial a relation to a geometry (in the layer's CRS), or null; exact when {@code
 *     jdal-ops} is available
 * @param where attribute filter in the syntax of the WHERE clause of OGR SQL, or null; evaluated by
 *     the SQL engine unless a driver pushes it down to its database
 */
public record Query(
    Envelope bbox, Predicate<Feature> filter, long limit, String where, SpatialFilter spatial) {

  public static final Query ALL = new Query(null, null, -1, null, null);

  public Query(Envelope bbox, Predicate<Feature> filter, long limit, String where) {
    this(bbox, filter, limit, where, null);
  }

  public Query(Envelope bbox, Predicate<Feature> filter, long limit) {
    this(bbox, filter, limit, null);
  }

  /**
   * Attribute filter in OGR SQL syntax, e.g. {@code Query.sql("pop > 1000 AND name LIKE 'A%'")}.
   */
  public static Query sql(String where) {
    return new Query(null, null, -1, where, null);
  }

  public Query withWhere(String w) {
    return new Query(bbox, filter, limit, w, spatial);
  }

  public static Query bbox(Envelope bbox) {
    return new Query(bbox, null, -1, null, null);
  }

  public static Query where(Predicate<Feature> filter) {
    return new Query(null, filter, -1, null, null);
  }

  /** Features in the given relation to {@code geometry}. */
  public static Query spatial(SpatialOp op, Geometry geometry) {
    return new Query(null, null, -1, null, new SpatialFilter(op, geometry));
  }

  public Query withSpatial(SpatialFilter s) {
    return new Query(bbox, filter, limit, where, s);
  }

  /**
   * The box that every matching feature's envelope must intersect, from the bounding box and the
   * spatial filter, or null if there is no such restriction (no filters, or {@code DISJOINT}).
   * Drivers use it to select candidates with an index before the exact test.
   */
  public Envelope candidateBox() {
    Envelope s = spatial != null && spatial.op().needsEnvelopeOverlap() ? spatial.envelope() : null;
    if (bbox == null) return s;
    if (s == null) return bbox;
    return new Envelope(
        Math.max(bbox.minX(), s.minX()), Math.max(bbox.minY(), s.minY()),
        Math.min(bbox.maxX(), s.maxX()), Math.min(bbox.maxY(), s.maxY()));
  }

  public Query withBbox(Envelope b) {
    return new Query(b, filter, limit, where, spatial);
  }

  public Query withFilter(Predicate<Feature> f) {
    return new Query(bbox, f, limit, where, spatial);
  }

  public Query withLimit(long l) {
    return new Query(bbox, filter, l, where, spatial);
  }

  public boolean isUnrestricted() {
    return bbox == null && filter == null && limit < 0 && where == null && spatial == null;
  }

  /** Tests bbox and filter (not the limit). */
  public boolean matches(Feature f) {
    if (bbox != null) {
      Geometry g = f.geometry();
      if (g == null || !g.envelope().intersects(bbox)) return false;
    }
    if (spatial != null && !spatial.test(f.geometry())) return false;
    return filter == null || filter.test(f);
  }
}
