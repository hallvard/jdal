package dev.jdal.core;

import java.util.List;
import java.util.Map;

/**
 * A format or database backend. Implementations are registered via {@link java.util.ServiceLoader}.
 */
public interface Driver {

  /** Short unique name as used by GDAL, e.g. "GeoJSON", "GPKG". */
  String name();

  String description();

  /** File extensions (lower-case, no dot) typically used for the format. */
  default List<String> extensions() {
    return List.of();
  }

  /** Cheap check whether {@code info} looks like something this driver can open. Must not throw. */
  boolean identify(OpenInfo info);

  /** Opens an existing dataset. */
  Dataset open(String location, boolean update, Map<String, String> options);

  /** Options understood by {@link #open}. */
  default List<OptionDef> openOptions() {
    return List.of();
  }

  /** Options understood when creating a dataset ({@link #create}). */
  default List<OptionDef> datasetCreationOptions() {
    return List.of();
  }

  /** Options understood by {@link Dataset#createLayer}. */
  default List<OptionDef> layerCreationOptions() {
    return List.of();
  }

  default boolean canCreate() {
    return false;
  }

  /** Creates a new, empty dataset at {@code location}. */
  default Dataset create(String location, Map<String, String> options) {
    throw new UnsupportedOperationException(name() + " cannot create datasets");
  }
}
