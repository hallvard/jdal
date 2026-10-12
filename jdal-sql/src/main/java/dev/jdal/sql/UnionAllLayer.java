package dev.jdal.sql;

import dev.jdal.core.Capability;
import dev.jdal.core.Layer;
import dev.jdal.feature.*;
import dev.jdal.geom.Geometry;
import java.util.*;
import java.util.stream.Stream;

/**
 * UNION ALL of select results: fields are merged by name, features renumbered from 0 (GDAL's {@code
 * OGRUnionLayer}).
 */
final class UnionAllLayer implements Layer {

  private final List<Layer> sources;
  private final FeatureType type;
  private final int[][] fieldMaps;
  private final int[][] geomMaps;

  UnionAllLayer(List<Layer> sources) {
    this.sources = List.copyOf(sources);
    List<FieldDefn> fields = new ArrayList<>();
    Map<String, Integer> byName = new HashMap<>();
    List<GeomFieldDefn> geoms = new ArrayList<>();
    for (Layer l : sources) {
      for (FieldDefn f : l.type().fields()) {
        Integer idx = byName.get(f.name());
        if (idx == null) {
          byName.put(f.name(), fields.size());
          fields.add(f);
        } else {
          fields.set(idx, merge(fields.get(idx), f));
        }
      }
      for (GeomFieldDefn g : l.type().geomFields()) {
        boolean found = false;
        for (GeomFieldDefn have : geoms) found |= have.name().equalsIgnoreCase(g.name());
        if (!found) geoms.add(g);
      }
    }
    this.type = new FeatureType("SELECT", fields, geoms);
    fieldMaps = new int[sources.size()][];
    geomMaps = new int[sources.size()][];
    for (int i = 0; i < sources.size(); i++) {
      FeatureType st = sources.get(i).type();
      fieldMaps[i] = new int[st.fields().size()];
      for (int k = 0; k < fieldMaps[i].length; k++) {
        fieldMaps[i][k] = byName.getOrDefault(st.fields().get(k).name(), -1);
      }
      geomMaps[i] = new int[st.geomFields().size()];
      for (int k = 0; k < geomMaps[i].length; k++) {
        geomMaps[i][k] = type.geomFieldIndex(st.geomFields().get(k).name()).orElse(-1);
      }
    }
  }

  private static FieldDefn merge(FieldDefn a, FieldDefn b) {
    FieldType t = a.type();
    if (a.type() != b.type()) {
      boolean ai = a.type() == FieldType.INTEGER || a.type() == FieldType.INTEGER64;
      boolean bi = b.type() == FieldType.INTEGER || b.type() == FieldType.INTEGER64;
      if (b.type() == FieldType.REAL && ai) t = FieldType.REAL;
      else if (a.type() == FieldType.REAL && bi) t = FieldType.REAL;
      else if (ai && bi) t = FieldType.INTEGER64;
      else t = FieldType.STRING;
    }
    FieldDefn m = a.withType(t, t == a.type() ? a.subType() : FieldSubType.NONE);
    if (a.width() != b.width() || a.precision() != b.precision()) m = m.withWidth(0, 0);
    return m;
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
    return EnumSet.noneOf(Capability.class);
  }

  @Override
  public Stream<Feature> features() {
    long[] next = {0};
    return java.util.stream.IntStream.range(0, sources.size())
        .boxed()
        .flatMap(
            i -> {
              Layer l = sources.get(i);
              FeatureType st = l.type();
              return l.features()
                  .map(
                      f -> {
                        Object[] values = new Object[type.fields().size()];
                        for (int k = 0; k < fieldMaps[i].length; k++) {
                          int dst = fieldMaps[i][k];
                          if (dst >= 0)
                            values[dst] =
                                Values.convert(
                                    f.get(k), st.fields().get(k), type.fields().get(dst));
                        }
                        Geometry[] geoms = new Geometry[type.geomFields().size()];
                        for (int k = 0; k < geomMaps[i].length; k++) {
                          int dst = geomMaps[i][k];
                          if (dst >= 0) geoms[dst] = f.geometry(k);
                        }
                        return new Feature(
                            type, next[0]++, Arrays.asList(values), Arrays.asList(geoms));
                      });
            });
  }

  @Override
  public void close() {
    for (Layer l : sources) l.close();
  }
}
