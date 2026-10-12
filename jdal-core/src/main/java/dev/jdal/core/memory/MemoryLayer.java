package dev.jdal.core.memory;

import dev.jdal.core.Capability;
import dev.jdal.core.JdalException;
import dev.jdal.core.Layer;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldValues;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Stream;

/** Layer holding its features in memory, ordered by fid. Safe for concurrent use. */
public final class MemoryLayer implements Layer {

  private volatile FeatureType type;
  private final ConcurrentSkipListMap<Long, Feature> store = new ConcurrentSkipListMap<>();
  private long nextFid;

  public MemoryLayer(FeatureType type) {
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
    return EnumSet.of(
        Capability.RANDOM_READ,
        Capability.SEQUENTIAL_WRITE,
        Capability.RANDOM_WRITE,
        Capability.FAST_FEATURE_COUNT,
        Capability.FAST_EXTENT,
        Capability.CREATE_FIELD,
        Capability.DELETE_FIELD,
        Capability.DELETE_FEATURE,
        Capability.Z_GEOMETRIES,
        Capability.MEASURED_GEOMETRIES);
  }

  @Override
  public Stream<Feature> features() {
    return new ArrayList<>(store.values()).stream();
  }

  @Override
  public long count() {
    return store.size();
  }

  @Override
  public Optional<Feature> feature(long fid) {
    return Optional.ofNullable(store.get(fid));
  }

  @Override
  public synchronized Feature insert(Feature feature) {
    check(feature);
    long fid = feature.hasFid() ? feature.fid() : nextFid;
    if (store.containsKey(fid))
      throw new JdalException("Feature with fid " + fid + " already exists in " + name());
    Feature stored = feature.fid() == fid ? feature : feature.withFid(fid);
    store.put(fid, stored);
    nextFid = Math.max(nextFid, fid + 1);
    return stored;
  }

  @Override
  public synchronized void update(Feature feature) {
    check(feature);
    if (!feature.hasFid() || store.replace(feature.fid(), feature) == null) {
      throw new JdalException("No feature with fid " + feature.fid() + " in " + name());
    }
  }

  @Override
  public boolean delete(long fid) {
    return store.remove(fid) != null;
  }

  @Override
  public synchronized void addField(FieldDefn field) {
    if (type.fieldIndex(field.name()).isPresent())
      throw new JdalException("Field " + field.name() + " exists");
    FeatureType newType = type.addField(field);
    List<Feature> old = new ArrayList<>(store.values());
    for (Feature f : old) {
      List<Object> values = new ArrayList<>(f.values());
      values.add(null);
      store.put(f.fid(), new Feature(newType, f.fid(), values, f.geometries()));
    }
    type = newType;
  }

  @Override
  public synchronized void deleteField(String field) {
    int idx =
        type.fieldIndex(field)
            .orElseThrow(() -> new JdalException("No field " + field + " in " + name()));
    List<FieldDefn> fields = new ArrayList<>(type.fields());
    fields.remove(idx);
    FeatureType newType = type.withFields(fields);
    for (Feature f : new ArrayList<>(store.values())) {
      List<Object> values = new ArrayList<>(f.values());
      values.remove(idx);
      store.put(f.fid(), new Feature(newType, f.fid(), values, f.geometries()));
    }
    type = newType;
  }

  @Override
  public synchronized void alterField(String field, FieldDefn newDefinition) {
    int idx =
        type.fieldIndex(field)
            .orElseThrow(() -> new JdalException("No field " + field + " in " + name()));
    List<FieldDefn> fields = new ArrayList<>(type.fields());
    fields.set(idx, newDefinition);
    FeatureType newType = type.withFields(fields);
    for (Feature f : new ArrayList<>(store.values())) {
      List<Object> values = new ArrayList<>(f.values());
      Object v = values.get(idx);
      values.set(
          idx,
          v == null
              ? null
              : newDefinition.type() == type.fields().get(idx).type()
                  ? v
                  : FieldValues.coerce(newDefinition, v));
      store.put(f.fid(), new Feature(newType, f.fid(), values, f.geometries()));
    }
    type = newType;
  }

  @Override
  public synchronized void rename(String newName) {
    FeatureType newType = type.withName(newName);
    for (Feature f : new ArrayList<>(store.values())) {
      store.put(f.fid(), new Feature(newType, f.fid(), f.values(), f.geometries()));
    }
    type = newType;
  }

  private void check(Feature f) {
    if (!f.type().equals(type)) {
      throw new JdalException("Feature schema does not match layer " + name());
    }
  }
}
