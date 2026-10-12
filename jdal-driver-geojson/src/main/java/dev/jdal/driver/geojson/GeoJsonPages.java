package dev.jdal.driver.geojson;

import dev.jdal.core.JdalException;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.json.JsonObject;
import dev.jdal.json.JsonValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GeoJSON feature collections that arrive as text, one page at a time (from OGC API Features
 * services, for example). The schema is inferred from one page and applied to all of them;
 * properties that are not in the schema are skipped.
 */
public final class GeoJsonPages {

  private GeoJsonPages() {}

  /** The schema of a layer inferred from a page. */
  public static final class Schema {
    private final GeoJsonSchema schema;

    private Schema(GeoJsonSchema schema) {
      this.schema = schema;
    }

    public FeatureType type() {
      return schema.type;
    }
  }

  /**
   * The features of a page and the other members of the collection ({@code links}, {@code
   * numberMatched}, ...).
   */
  public record Page(List<Feature> features, Map<String, JsonValue> members) {

    public JsonValue member(String name) {
      return members.getOrDefault(name, dev.jdal.json.JsonNull.INSTANCE);
    }

    public JsonObject object() {
      return new JsonObject(members);
    }
  }

  public static Schema infer(String text, String defaultName) {
    GeoJsonSchema s = GeoJsonSchema.scan(GeoJsonSource.ofText(text), defaultName);
    s.lenient = true;
    return new Schema(s);
  }

  /** Reads all features of a page; those without a numeric id get fids from {@code firstFid}. */
  public static Page read(String text, Schema schema, long firstFid) {
    List<Feature> out = new ArrayList<>();
    Map<String, JsonValue> top;
    try (FeatureNodes nodes = new FeatureNodes(GeoJsonSource.ofText(text).open())) {
      long seq = firstFid;
      while (nodes.hasNext()) {
        JsonObject n = nodes.next();
        out.add(schema.schema.toFeature(n, seq++));
      }
      top = Map.copyOf(nodes.topLevel());
    } catch (RuntimeException e) {
      if (e instanceof JdalException) throw e;
      throw new JdalException("Invalid GeoJSON page: " + e.getMessage(), e);
    }
    return new Page(out, top);
  }
}
