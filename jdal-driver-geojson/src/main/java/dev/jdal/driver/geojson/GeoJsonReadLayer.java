package dev.jdal.driver.geojson;

import dev.jdal.core.Capability;
import dev.jdal.core.Layer;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.geom.Envelope;
import dev.jdal.json.JsonObject;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Read-only layer that streams the document on every scan; count and extent come from the schema
 * pass.
 */
final class GeoJsonReadLayer implements Layer {

  private final GeoJsonSource source;
  private final GeoJsonSchema schema;

  GeoJsonReadLayer(GeoJsonSource source, GeoJsonSchema schema) {
    this.source = source;
    this.schema = schema;
  }

  @Override
  public String name() {
    return schema.type.name();
  }

  @Override
  public FeatureType type() {
    return schema.type;
  }

  @Override
  public Set<Capability> capabilities() {
    return EnumSet.of(
        Capability.FAST_FEATURE_COUNT, Capability.FAST_EXTENT, Capability.Z_GEOMETRIES);
  }

  @Override
  public long count() {
    return schema.count;
  }

  @Override
  public Optional<Envelope> extent() {
    return schema.extent.isEmpty() ? Optional.empty() : Optional.of(schema.extent);
  }

  @Override
  public Stream<Feature> features() {
    FeatureNodes nodes = new FeatureNodes(source.open());
    Iterator<Feature> it =
        new Iterator<>() {
          long seq;

          @Override
          public boolean hasNext() {
            return nodes.hasNext();
          }

          @Override
          public Feature next() {
            if (!nodes.hasNext()) throw new NoSuchElementException();
            JsonObject n = nodes.next();
            return schema.toFeature(n, seq++);
          }
        };
    return StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL),
            false)
        .onClose(nodes::close);
  }
}
