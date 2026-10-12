package dev.jdal.driver.oapif;

import dev.jdal.core.Dataset;
import dev.jdal.core.Driver;
import dev.jdal.core.JdalException;
import dev.jdal.core.Layer;
import dev.jdal.geom.Envelope;
import dev.jdal.http.Http;
import dev.jdal.json.Json;
import dev.jdal.json.JsonArray;
import dev.jdal.json.JsonNumber;
import dev.jdal.json.JsonObject;
import dev.jdal.json.JsonString;
import dev.jdal.json.JsonValue;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The collections of an OGC API - Features service as layers. */
final class OapifDataset implements Dataset {

  private final OapifDriver driver;
  private final String location;
  private final Http http;
  private final List<Layer> layers = new ArrayList<>();

  OapifDataset(
      OapifDriver driver, String location, String url, Http http, Map<String, String> options) {
    this.driver = driver;
    this.location = location;
    this.http = http;
    Url u = Url.parse(url);
    OapifLayer.Settings settings = OapifLayer.Settings.from(options);
    String path = u.path();
    int collectionsAt = stripSlash(path).indexOf("/collections/");
    String rootPath =
        collectionsAt >= 0 ? stripSlash(path).substring(0, collectionsAt) : stripSlash(path);
    settings = settings.withCql2(supportsCql2(u.with(rootPath + "/conformance", u.query())));
    if (stripSlash(path).matches(".*/collections/[^/]+")) {
      JsonObject collection = getObject(u.with(stripSlash(path), u.query()));
      layers.add(layer(u.with(stripSlash(path), u.query()), collection, settings));
    } else {
      String next = u.with(stripSlash(path) + "/collections", u.query()).toString();
      Set<String> seen = new HashSet<>();
      while (next != null && seen.add(next)) {
        JsonObject page = getObject(Url.parse(next));
        if (!(page.path("collections") instanceof JsonArray cols)) {
          throw new JdalException("No collections array in the response from " + next);
        }
        for (JsonValue v : cols) {
          if (!(v instanceof JsonObject col))
            throw new JdalException("Invalid collection in " + next);
          layers.add(
              layer(
                  u.with(stripSlash(path) + "/collections/" + id(col), u.query()), col, settings));
        }
        next = nextLink(page, next);
      }
    }
  }

  /**
   * True if the service says it implements filtering with CQL2 text (OGC API - Features part 3).
   */
  private boolean supportsCql2(Url conformance) {
    try {
      Http.Response r = http.get(conformance.toString(), Map.of("Accept", "application/json"));
      if (!r.ok()
          || !(Json.parse(r.text()) instanceof JsonObject o)
          || !(o.path("conformsTo") instanceof JsonArray a)) return false;
      boolean text = false;
      boolean filter = false;
      for (JsonValue v : a) {
        if (v instanceof JsonString s) {
          text |= s.value().contains("cql2-text");
          filter |= s.value().contains("/conf/filter") || s.value().contains("features-filter");
        }
      }
      return text && filter;
    } catch (IOException | RuntimeException e) {
      return false; // filtering is optional
    }
  }

  private static String stripSlash(String path) {
    return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
  }

  private static String id(JsonObject collection) {
    if (collection.path("id") instanceof JsonString s) return s.value();
    if (collection.path("name") instanceof JsonString s) return s.value();
    throw new JdalException("Collection without id");
  }

  private Layer layer(Url collectionUrl, JsonObject collection, OapifLayer.Settings settings) {
    String title = collection.path("title") instanceof JsonString t ? t.value() : null;
    return new OapifLayer(http, id(collection), title, collectionUrl, extent(collection), settings);
  }

  /** The first bounding box of {@code extent.spatial.bbox}: 4 values, or 6 with elevation. */
  private static Envelope extent(JsonObject collection) {
    if (collection.path("extent").path("spatial").path("bbox") instanceof JsonArray boxes
        && boxes.size() > 0
        && boxes.get(0) instanceof JsonArray b) {
      List<Double> v = new ArrayList<>();
      for (JsonValue x : b) if (x instanceof JsonNumber n) v.add(n.doubleValue());
      if (v.size() == 4) return new Envelope(v.get(0), v.get(1), v.get(2), v.get(3));
      if (v.size() == 6) return new Envelope(v.get(0), v.get(1), v.get(3), v.get(4));
    }
    return null;
  }

  private JsonObject getObject(Url url) {
    try {
      Http.Response r = http.get(url.toString(), Map.of("Accept", "application/json"));
      if (!r.ok()) throw new JdalException(OapifLayer.httpError(r));
      String type = r.header("Content-Type");
      if (type == null || !type.contains("json")) {
        throw new JdalException("Unexpected content type " + type + " from " + url);
      }
      if (Json.parse(r.text()) instanceof JsonObject o) return o;
      throw new JdalException("Expected a JSON object from " + url);
    } catch (IOException e) {
      throw new JdalException("Cannot read " + url + ": " + e.getMessage(), e);
    }
  }

  /** The {@code next} link of a page, resolved against the page's own URL. */
  static String nextLink(JsonObject page, String pageUrl) {
    if (!(page.path("links") instanceof JsonArray links)) return null;
    String best = null;
    for (JsonValue v : links) {
      if (v instanceof JsonObject l
          && l.path("rel") instanceof JsonString rel
          && rel.value().equals("next")
          && l.path("href") instanceof JsonString href) {
        String type = l.path("type") instanceof JsonString t ? t.value() : "";
        String resolved = URI.create(pageUrl).resolve(href.value()).toString();
        if (type.contains("json")) return resolved;
        if (best == null) best = resolved;
      }
    }
    return best;
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
    return List.copyOf(layers);
  }
}
