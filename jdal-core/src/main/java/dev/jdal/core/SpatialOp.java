package dev.jdal.core;

/**
 * Spatial relations between a feature's geometry and a filter geometry (the DE-9IM named
 * predicates).
 */
public enum SpatialOp {
  INTERSECTS,
  /** The feature lies within the filter geometry. */
  WITHIN,
  /** The feature contains the filter geometry. */
  CONTAINS,
  DISJOINT,
  TOUCHES,
  CROSSES,
  OVERLAPS,
  EQUALS;

  /**
   * True if every matching feature's envelope must intersect the filter's envelope (everything but
   * DISJOINT).
   */
  public boolean needsEnvelopeOverlap() {
    return this != DISJOINT;
  }
}
