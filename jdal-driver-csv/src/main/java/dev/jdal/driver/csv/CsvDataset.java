package dev.jdal.driver.csv;

import dev.jdal.core.*;
import dev.jdal.feature.FeatureType;
import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A CSV file (one layer) or a directory of CSV files. */
final class CsvDataset implements Dataset {

  private final CsvDriver driver;
  private final String location;
  private final String path;
  private final boolean singleFile;
  private final boolean writable;
  private final List<Layer> layers = new ArrayList<>();

  CsvDataset(CsvDriver driver, String location, String path, boolean singleFile, boolean writable) {
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
      throw new JdalException(
          "This CSV dataset cannot create (more) layers (opened read-only or single file)");
    if (layer(type.name()).isPresent())
      throw new JdalException("Layer " + type.name() + " already exists");
    if (type.name().contains("/") || type.name().contains("\\"))
      throw new JdalException("Illegal layer name " + type.name());
    String file = singleFile ? path : VsiPath.join(path, type.name() + ".csv");
    Layer l =
        new CsvWriteLayer(
            file,
            singleFile ? type.withName(CsvSchema.stripExtension(VsiPath.fileName(path))) : type,
            Options.of(options));
    layers.add(l);
    return l;
  }

  @Override
  public synchronized void deleteLayer(String name) {
    throw new UnsupportedOperationException("CSV layers cannot be deleted");
  }

  @Override
  public synchronized void close() {
    for (Layer l : layers) l.close();
  }

  static boolean isCsvFile(String p) {
    String n = VsiPath.fileName(p).toLowerCase(java.util.Locale.ROOT);
    return n.endsWith(".csv") || n.endsWith(".tsv") || n.endsWith(".psv");
  }

  static List<String> csvFiles(String dir) {
    Vsi vsi = Vsi.global();
    try {
      return vsi.list(dir).stream()
          .map(n -> VsiPath.join(dir, n))
          .filter(vsi::isFile)
          .filter(CsvDataset::isCsvFile)
          .sorted()
          .toList();
    } catch (java.io.IOException e) {
      throw new JdalException("Cannot list " + dir + ": " + e.getMessage(), e);
    }
  }
}
