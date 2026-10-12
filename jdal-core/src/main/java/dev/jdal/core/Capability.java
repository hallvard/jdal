package dev.jdal.core;

/** What a {@link Layer} can do (GDAL's {@code OLC*} capabilities). */
public enum Capability {
  RANDOM_READ,
  SEQUENTIAL_WRITE,
  RANDOM_WRITE,
  FAST_SPATIAL_FILTER,
  FAST_FEATURE_COUNT,
  FAST_EXTENT,
  CREATE_FIELD,
  DELETE_FIELD,
  DELETE_FEATURE,
  TRANSACTIONS,
  CREATE_GEOM_FIELD,
  /** Layer stores curve geometries natively (always false for now: curves are not modelled). */
  CURVE_GEOMETRIES,
  MEASURED_GEOMETRIES,
  Z_GEOMETRIES
}
