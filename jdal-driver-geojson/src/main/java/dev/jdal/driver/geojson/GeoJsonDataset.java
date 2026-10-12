package dev.jdal.driver.geojson;

import dev.jdal.core.*;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A GeoJSON file with exactly one layer. Reading loads the file; writing buffers features until
 * close.
 */
final class GeoJsonDataset implements Dataset {

  private final GeoJsonDriver driver;
  private final String location;
  private Layer layer;
  private final boolean writing;

  /** Read mode. */
  GeoJsonDataset(GeoJsonDriver driver, String location, Layer layer) {
    this.driver = driver;
    this.location = location;
    this.layer = layer;
    this.writing = false;
  }

  /** Create mode. */
  GeoJsonDataset(GeoJsonDriver driver, String location) {
    this.driver = driver;
    this.location = location;
    this.writing = true;
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
  public List<Layer> layers() {
    return layer == null ? List.of() : List.of(layer);
  }

  @Override
  public boolean canCreateLayers() {
    return writing && layer == null;
  }

  @Override
  public Layer createLayer(FeatureType type, Map<String, String> options) {
    if (!canCreateLayers()) throw new JdalException("GeoJSON files hold a single layer");
    layer = new WritingLayer(type);
    return layer;
  }

  @Override
  public void close() {
    if (layer instanceof WritingLayer w) w.flush();
  }

  private final class WritingLayer implements Layer {
    private final FeatureType type;
    private final List<Feature> features = new ArrayList<>();
    private boolean flushed;

    WritingLayer(FeatureType type) {
      this.type = type;
    }

    @Override
    public String name() {
      return type.name();
    }

    @Override
    public FeatureType type() {
      return type;
    }

    @Override
    public Set<Capability> capabilities() {
      return EnumSet.of(Capability.SEQUENTIAL_WRITE, Capability.Z_GEOMETRIES);
    }

    @Override
    public Stream<Feature> features() {
      return List.copyOf(features).stream();
    }

    @Override
    public Feature insert(Feature f) {
      if (!f.type().equals(type))
        throw new JdalException("Feature schema does not match layer " + name());
      Feature stored = f.hasFid() ? f : f.withFid(features.size());
      features.add(stored);
      return stored;
    }

    @Override
    public void sync() {
      flush();
    }

    void flush() {
      if (flushed) return;
      GeoJsonWriter.write(location, type, features);
      flushed = true;
    }

    @Override
    public void close() {
      flush();
    }
  }
}
