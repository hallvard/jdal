package dev.jdal.core.memory;

import dev.jdal.core.Dataset;
import dev.jdal.core.Driver;
import dev.jdal.core.JdalException;
import dev.jdal.core.Layer;
import dev.jdal.feature.FeatureType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Dataset of {@link MemoryLayer}s. Also the container for results of operations such as SQL. */
public final class MemoryDataset implements Dataset {

  private final String name;
  private final List<MemoryLayer> layers = new ArrayList<>();

  public MemoryDataset(String name) {
    this.name = name;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Driver driver() {
    return MemoryDriver.INSTANCE;
  }

  @Override
  public synchronized List<Layer> layers() {
    return List.copyOf(layers);
  }

  @Override
  public boolean canCreateLayers() {
    return true;
  }

  @Override
  public synchronized MemoryLayer createLayer(FeatureType type, Map<String, String> options) {
    if (layer(type.name()).isPresent())
      throw new JdalException("Layer " + type.name() + " already exists");
    MemoryLayer l = new MemoryLayer(type);
    layers.add(l);
    return l;
  }

  @Override
  public synchronized void deleteLayer(String layerName) {
    if (!layers.removeIf(l -> l.name().equalsIgnoreCase(layerName))) {
      throw new JdalException("No layer " + layerName);
    }
  }
}
