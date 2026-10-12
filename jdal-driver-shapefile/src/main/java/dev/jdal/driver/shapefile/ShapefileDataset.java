package dev.jdal.driver.shapefile;

import dev.jdal.core.*;
import dev.jdal.feature.FeatureType;
import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiPath;
import java.util.*;

/** A single .shp/.dbf file (one layer) or a directory of shapefiles. */
final class ShapefileDataset implements Dataset {

  private final ShapefileDriver driver;
  private final String location;
  private final String path;
  private final boolean singleFile;
  private final boolean writable;
  private final List<Layer> layers = new ArrayList<>();

  ShapefileDataset(
      ShapefileDriver driver, String location, String path, boolean singleFile, boolean writable) {
    this.driver = driver;
    this.location = location;
    this.path = path;
    this.singleFile = singleFile;
    this.writable = writable;
  }

  void add(Layer l) {
    layers.add(l);
  }

  @Override
  public String name() {
    return location;
  }

  @Override
  public Driver driver() {
    return driver;
  }

  @Override
  public synchronized List<Layer> layers() {
    return List.copyOf(layers);
  }

  @Override
  public boolean canCreateLayers() {
    return writable && (!singleFile || layers.isEmpty());
  }

  @Override
  public synchronized Layer createLayer(FeatureType type, Map<String, String> options) {
    if (!canCreateLayers())
      throw new JdalException("This shapefile dataset cannot create (more) layers");
    if (type.name().contains("/") || type.name().contains("\\") || type.name().isBlank()) {
      throw new JdalException("Illegal layer name '" + type.name() + "'");
    }
    String base =
        singleFile ? VsiPath.sibling(path, DbfFile.base(path)) : VsiPath.join(path, type.name());
    FeatureType named = singleFile ? type.withName(DbfFile.base(path)) : type;
    Options o = Options.of(options);
    if (layer(named.name()).isPresent()) {
      if (!o.bool("OVERWRITE", false))
        throw new JdalException("Layer " + named.name() + " already exists");
      layers.removeIf(l -> l.name().equalsIgnoreCase(named.name()));
    }
    ShapefileLayer l = ShapefileLayer.create(base, named, o, null);
    layers.add(l);
    return l;
  }

  @Override
  public synchronized void deleteLayer(String layerName) {
    ShapefileLayer l = (ShapefileLayer) requireLayer(layerName);
    l.close();
    layers.remove(l);
    Vsi vsi = Vsi.global();
    String dir = singleFile ? VsiPath.parent(path).isEmpty() ? "." : VsiPath.parent(path) : path;
    try {
      for (String n : vsi.list(dir)) {
        int dot = n.lastIndexOf('.');
        if (dot > 0
            && n.substring(0, dot).equals(l.name())
            && List.of("shp", "shx", "dbf", "prj", "cpg", "qix", "sbn", "sbx")
                .contains(n.substring(dot + 1).toLowerCase(Locale.ROOT))) {
          vsi.delete(VsiPath.join(dir, n));
        }
      }
    } catch (java.io.IOException e) {
      throw new JdalException("Cannot delete layer " + layerName + ": " + e.getMessage(), e);
    }
  }

  @Override
  public synchronized void close() {
    for (Layer l : layers) l.close();
  }
}
