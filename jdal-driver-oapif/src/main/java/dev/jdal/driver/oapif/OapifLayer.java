package dev.jdal.driver.oapif;

import dev.jdal.core.Capability;
import dev.jdal.core.JdalException;
import dev.jdal.core.Layer;
import dev.jdal.core.Query;
import dev.jdal.core.SqlEngine;
import dev.jdal.driver.geojson.GeoJsonPages;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.feature.GeomFieldDefn;
import dev.jdal.filter.Cql2Text;
import dev.jdal.filter.Translation;
import dev.jdal.geom.Envelope;
import dev.jdal.http.Http;
import dev.jdal.json.Json;
import dev.jdal.json.JsonNumber;
import dev.jdal.json.JsonObject;
import dev.jdal.srs.SpatialReference;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * One collection. The schema is inferred from the first features the server returns; features are
 * fetched page by page following the {@code next} links, with the bounding box, datetime and limit
 * sent to the server. Attribute filters are applied here.
 */
final class OapifLayer implements Layer {

  /** Open options. */
  record Settings(int pageSize, int initialPageSize, String datetime, boolean cql2) {
    Settings withCql2(boolean value) {
      return new Settings(pageSize, initialPageSize, datetime, value);
    }

    static Settings from(Map<String, String> options) {
      return new Settings(
          positive(options, "PAGE_SIZE", 1000),
          positive(options, "INITIAL_REQUEST_PAGE_SIZE", 20),
          options.get("DATETIME"),
          false);
    }

    private static int positive(Map<String, String> o, String key, int fallback) {
      String v =
          o.entrySet().stream()
              .filter(e -> e.getKey().equalsIgnoreCase(key))
              .map(Map.Entry::getValue)
              .findFirst()
              .orElse(null);
      if (v == null) return fallback;
      try {
        return Math.max(1, Integer.parseInt(v.strip()));
      } catch (NumberFormatException e) {
        throw new JdalException("Option " + key + " must be an integer, not '" + v + "'");
      }
    }
  }

  private static final Map<String, String> GEOJSON =
      Map.of("Accept", "application/geo+json, application/json;q=0.9");

  static String httpError(Http.Response r) {
    String body = r.text().strip();
    return "HTTP error code : " + r.status() + (body.isEmpty() ? "" : ", " + body);
  }

  private final Http http;
  private final String id;
  private final String title;
  private final Url collection;
  private final Envelope extent;
  private final Settings settings;
  private GeoJsonPages.Schema schema;
  private FeatureType type;
  private long numberMatched = -1;

  OapifLayer(
      Http http, String id, String title, Url collection, Envelope extent, Settings settings) {
    this.http = http;
    this.id = id;
    this.title = title;
    this.collection = collection;
    this.extent = extent;
    this.settings = settings;
  }

  @Override
  public String name() {
    return id;
  }

  String title() {
    return title;
  }

  private String itemsUrl(String params) {
    return collection.with(collection.path() + "/items", collection.query()).toString(params);
  }

  private String fetchPage(String url) {
    try {
      Http.Response r = http.get(url, GEOJSON);
      if (!r.ok()) throw new JdalException(httpError(r));
      return r.text();
    } catch (IOException e) {
      throw new JdalException("Cannot read " + url + ": " + e.getMessage(), e);
    }
  }

  private synchronized void load() {
    if (type != null) return;
    String text = fetchPage(itemsUrl("limit=" + settings.initialPageSize()));
    schema = GeoJsonPages.infer(text, id);
    FeatureType t = schema.type().withName(id);
    // services deliver CRS84 unless asked otherwise
    List<GeomFieldDefn> geoms = new ArrayList<>();
    for (GeomFieldDefn g : t.geomFields()) {
      geoms.add(
          g.srs() != null
              ? g
              : new GeomFieldDefn(
                  g.name(), g.type(), g.dimension(), SpatialReference.WGS84, g.nullable()));
    }
    type = t.withGeomFields(geoms);
    if (GeoJsonPages.read(text, schema, 0).member("numberMatched") instanceof JsonNumber n
        && n.canConvertToLong()) {
      numberMatched = n.longValue();
    }
  }

  @Override
  public FeatureType type() {
    load();
    return type;
  }

  @Override
  public Set<Capability> capabilities() {
    return EnumSet.of(Capability.FAST_SPATIAL_FILTER);
  }

  @Override
  public Stream<Feature> features() {
    return features(Query.ALL);
  }

  private static String number(double d) {
    return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
  }

  /**
   * The CQL2 form of the query's attribute filter, if the service takes filters and part of it can
   * be sent.
   */
  private Translation pushedFilter(Query query) {
    if (query.where() == null || !settings.cql2()) return Translation.NONE;
    Set<String> queryable = queryables();
    return Cql2Text.translate(query.where(), type, n -> queryable == null || queryable.contains(n));
  }

  private Set<String> queryables;
  private boolean queryablesRead;
  private String geometryProperty = "geometry";

  private static boolean isGeometrySchema(JsonObject schema) {
    String ref =
        schema.path("$ref") instanceof dev.jdal.json.JsonString s ? s.value().toLowerCase() : "";
    String format =
        schema.path("format") instanceof dev.jdal.json.JsonString s ? s.value().toLowerCase() : "";
    return ref.contains("geometry") || format.startsWith("geometry");
  }

  /**
   * The spatial predicate of the query as CQL2 text, or null if it cannot be sent (the service
   * takes no filters, or the geometry would make the request too long).
   */
  private String spatialCql(Query query) {
    if (query.spatial() == null || !settings.cql2()) return null;
    queryables(); // finds the name of the geometry property
    String text =
        "S_"
            + query.spatial().op().name()
            + "("
            + geometryProperty
            + ", "
            + query.spatial().geometry().toWkt()
            + ")";
    return encode(text).length() > 4000 ? null : text;
  }

  /** The properties the service accepts in filters, or null if it does not say. */
  private synchronized Set<String> queryables() {
    if (!queryablesRead) {
      queryablesRead = true;
      try {
        Http.Response r =
            http.get(
                collection.with(collection.path() + "/queryables", collection.query()).toString(),
                Map.of("Accept", "application/schema+json, application/json"));
        if (r.ok()
            && Json.parse(r.text()) instanceof JsonObject o
            && o.path("properties") instanceof JsonObject p) {
          queryables = new java.util.HashSet<>(p.members().keySet());
          for (var e : p.members().entrySet()) {
            if (e.getValue() instanceof JsonObject schema && isGeometrySchema(schema)) {
              geometryProperty = e.getKey();
              break;
            }
          }
        }
      } catch (IOException | RuntimeException e) {
        // no queryables: assume every property can be used
      }
    }
    return queryables;
  }

  /** Attribute and spatial conditions in one CQL2 expression. */
  private static Translation combine(Translation attributes, String spatial) {
    if (spatial == null) return attributes;
    if (!attributes.present()) return new Translation(spatial, true);
    String a =
        attributes.text().contains(" OR ") ? "(" + attributes.text() + ")" : attributes.text();
    return new Translation(a + " AND " + spatial, attributes.complete());
  }

  private static String encode(String s) {
    return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
        .replace("+", "%20");
  }

  @Override
  public Stream<Feature> features(Query query) {
    load();
    Predicate<Feature> sql =
        query.where() == null ? null : SqlEngine.require().compileFilter(type, query.where());
    Translation attributes = pushedFilter(query);
    String spatialText = spatialCql(query);
    // everything that goes to the server in one filter; a limit may go along only if it is all of
    // the query
    Translation pushed = combine(attributes, spatialText);
    boolean exact =
        query.filter() == null
            && (sql == null || attributes.complete())
            && (query.spatial() == null || spatialText != null);
    long limit = exact ? query.limit() : -1;
    StringBuilder params = new StringBuilder();
    int page = settings.pageSize();
    Envelope b = query.candidateBox();
    StringBuilder common = new StringBuilder();
    if (b != null) {
      common
          .append("&bbox=")
          .append(number(b.minX()))
          .append(',')
          .append(number(b.minY()))
          .append(',')
          .append(number(b.maxX()))
          .append(',')
          .append(number(b.maxY()));
    }
    if (settings.datetime() != null) common.append("&datetime=").append(settings.datetime());
    String filterParams =
        pushed.present() ? "&filter=" + encode(pushed.text()) + "&filter-lang=cql2-text" : "";
    String first =
        itemsUrl(
            "limit="
                + (limit >= 0 ? Math.max(1, Math.min(limit, page)) : page)
                + common
                + filterParams);
    String fallback = pushed.present() ? itemsUrl("limit=" + page + common) : null;
    Iterator<Feature> pages = new PageIterator(first, fallback);
    Stream<Feature> s =
        StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(pages, Spliterator.ORDERED | Spliterator.NONNULL),
            false);
    // the whole query is applied again here: the service may ignore or only approximate parts of it
    if (b != null || query.filter() != null || query.spatial() != null || sql != null) {
      s = s.filter(f -> query.matches(f) && (sql == null || sql.test(f)));
    }
    if (query.limit() >= 0) s = s.limit(query.limit());
    return s;
  }

  /** Reads pages as the stream is consumed. */
  private final class PageIterator implements Iterator<Feature> {
    private String next;
    private Iterator<Feature> current = List.<Feature>of().iterator();
    private long fid;

    private String fallback;

    /**
     * {@code fallback} is used instead if the service refuses the first request (a filter it does
     * not understand).
     */
    PageIterator(String first, String fallback) {
      this.next = first;
      this.fallback = fallback;
    }

    @Override
    public boolean hasNext() {
      while (!current.hasNext() && next != null) {
        String url = next;
        String text;
        try {
          text = fetchPage(url);
        } catch (JdalException e) {
          if (fallback == null) throw e;
          next = fallback; // retry without the filter; it is applied here
          fallback = null;
          continue;
        }
        fallback = null;
        GeoJsonPages.Page page = GeoJsonPages.read(text, schema, fid);
        fid += page.features().size();
        current = page.features().iterator();
        next = page.features().isEmpty() ? null : OapifDataset.nextLink(page.object(), url);
      }
      return current.hasNext();
    }

    @Override
    public Feature next() {
      if (!hasNext()) throw new NoSuchElementException();
      return current.next();
    }
  }

  @Override
  public long count(Query query) {
    if (query.isUnrestricted()) {
      load();
      if (numberMatched >= 0) return numberMatched;
    } else if (query.where() != null
        && query.filter() == null
        && query.bbox() == null
        && query.limit() < 0) {
      load();
      Translation t = pushedFilter(query);
      if (t.present() && t.complete()) {
        try {
          String text =
              fetchPage(itemsUrl("limit=1&filter=" + encode(t.text()) + "&filter-lang=cql2-text"));
          if (GeoJsonPages.read(text, schema, 0).member("numberMatched") instanceof JsonNumber n
              && n.canConvertToLong()) {
            return n.longValue();
          }
        } catch (JdalException e) {
          // count the features instead
        }
      }
    }
    return Layer.super.count(query);
  }

  @Override
  public Optional<Envelope> extent() {
    return extent != null ? Optional.of(extent) : Layer.super.extent();
  }
}
