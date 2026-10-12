package dev.jdal.driver.wfs;

import dev.jdal.core.Capability;
import dev.jdal.core.JdalException;
import dev.jdal.core.Layer;
import dev.jdal.core.Query;
import dev.jdal.core.SpatialOp;
import dev.jdal.core.SqlEngine;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FeatureType;
import dev.jdal.feature.GeomFieldDefn;
import dev.jdal.filter.OgcFilter;
import dev.jdal.filter.Translation;
import dev.jdal.geom.Envelope;
import dev.jdal.gml.GmlFeature;
import dev.jdal.gml.GmlFeatureReader;
import dev.jdal.gml.GmlGeometryWriter;
import dev.jdal.gml.GmlSchema;
import dev.jdal.gml.SrsNames;
import dev.jdal.gml.XsdSchema;
import dev.jdal.http.Http;
import dev.jdal.srs.SpatialReference;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * A WFS feature type. The schema comes from DescribeFeatureType (or, failing that, from one
 * feature); features are streamed from GetFeature responses, with the bounding box and the limit
 * sent to the server. Attribute filters are applied here.
 */
final class WfsLayer implements Layer {

  record Settings(String version, boolean paging, int pageSize) {
    boolean wfs20() {
      return version.startsWith("2.");
    }

    boolean wfs10() {
      return version.startsWith("1.0");
    }
  }

  private final Http http;
  private final WfsCapabilities.FeatureTypeInfo info;
  private final String getFeatureUrl;
  private final String describeUrl;
  private final Settings settings;
  private FeatureType type;

  /** The name of the geometry property, known when there is an application schema. */
  private String geometryProperty;

  WfsLayer(
      Http http,
      WfsCapabilities.FeatureTypeInfo info,
      String getFeatureUrl,
      String describeUrl,
      Settings settings) {
    this.http = http;
    this.info = info;
    this.getFeatureUrl = getFeatureUrl;
    this.describeUrl = describeUrl;
    this.settings = settings;
  }

  @Override
  public String name() {
    return info.name();
  }

  private String local() {
    String n = info.name();
    return n.substring(n.indexOf(':') + 1);
  }

  private String typeNameParam() {
    return (settings.wfs20() ? "TYPENAMES=" : "TYPENAME=") + info.name();
  }

  private String request(String base, String what, String extra) {
    return WfsDataset.append(
        base,
        "SERVICE=WFS&VERSION="
            + settings.version()
            + "&REQUEST="
            + what
            + "&"
            + typeNameParam()
            + extra);
  }

  // ------------------------------------------------------------------ schema

  private SpatialReference layerSrs() {
    return SrsNames.parse(info.defaultSrs()).srs();
  }

  @Override
  public synchronized FeatureType type() {
    if (type != null) return type;
    FeatureType declared = null;
    try {
      String xsd = WfsDataset.fetch(http, request(describeUrl, "DescribeFeatureType", ""));
      declared =
          XsdSchema.parse(new java.io.StringReader(xsd), loc -> include(loc), true).get(local());
      if (declared != null && !declared.geomFields().isEmpty()) {
        geometryProperty = declared.geomFields().getFirst().name();
        if (declared.geomFields().size() == 1) {
          GeomFieldDefn g = declared.geomFields().getFirst();
          declared =
              declared.withGeomFields(
                  List.of(new GeomFieldDefn("", g.type(), g.dimension(), g.srs(), g.nullable())));
        }
      }
    } catch (JdalException e) {
      // fall back to looking at a feature
    }
    FeatureType inferred = inferFromFeature();
    if (declared != null && inferred == null) {
      // like GDAL, expose the feature id: gml:id from WFS 1.1, fid in 1.0
      String idField = settings.wfs10() ? "fid" : "gml_id";
      List<dev.jdal.feature.FieldDefn> fields = new ArrayList<>(declared.fields());
      if (declared.fieldIndex(idField).isEmpty())
        fields.addFirst(dev.jdal.feature.FieldDefn.of(idField, dev.jdal.feature.FieldType.STRING));
      declared = declared.withFields(fields);
    }
    FeatureType t =
        declared != null
            ? (inferred != null ? GmlSchema.merge(declared, inferred) : declared)
            : inferred;
    if (t == null) t = FeatureType.of(info.name(), List.of());
    SpatialReference srs = layerSrs();
    List<GeomFieldDefn> geoms = new ArrayList<>();
    for (GeomFieldDefn g : t.geomFields()) {
      geoms.add(
          g.srs() != null || srs == null
              ? g
              : new GeomFieldDefn(g.name(), g.type(), g.dimension(), srs, g.nullable()));
    }
    type = new FeatureType(info.name(), t.fields(), geoms);
    return type;
  }

  private java.io.Reader include(String location) {
    try {
      Http.Response r = http.get(location, Map.of());
      return r.ok() ? new java.io.StringReader(r.text()) : null;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /** The type as the first feature shows it, or null if there is none. */
  private FeatureType inferFromFeature() {
    String url = getFeatureUrl(null, 1);
    GmlSchema.Inference inf = new GmlSchema.Inference(true, true);
    try (GmlFeatureReader r = open(url)) {
      if (r.hasNext()) inf.add(r.next());
    } catch (JdalException e) {
      return null;
    }
    return inf.build().values().stream().findFirst().orElse(null);
  }

  // ------------------------------------------------------------------ features

  private String getFeatureUrl(Envelope bbox, long count) {
    return getFeatureUrl(bbox, count, "");
  }

  /** {@code filter} is a ready {@code &FILTER=...} parameter or empty. */
  private String getFeatureUrl(Envelope bbox, long count, String filter) {
    StringBuilder extra = new StringBuilder();
    if (count >= 0) extra.append(settings.wfs20() ? "&COUNT=" : "&MAXFEATURES=").append(count);
    if (bbox != null) extra.append("&BBOX=").append(bboxParam(bbox));
    extra.append(filter);
    return request(getFeatureUrl, "GetFeature", extra.toString());
  }

  private static String number(double d) {
    return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
  }

  /** The BBOX value: for CRS defined north first the box is written in that order. */
  private String bboxParam(Envelope b) {
    SrsNames srs = SrsNames.parse(info.defaultSrs());
    String x =
        number(b.minX()) + "," + number(b.minY()) + "," + number(b.maxX()) + "," + number(b.maxY());
    if (settings.wfs10() || info.defaultSrs() == null) return x;
    if (srs.swapsAxes()) {
      x =
          number(b.minY())
              + ","
              + number(b.minX())
              + ","
              + number(b.maxY())
              + ","
              + number(b.maxX());
    }
    return x + "," + info.defaultSrs();
  }

  /**
   * Opens a GetFeature (or similar) response; exception reports and HTTP errors become exceptions.
   */
  private GmlFeatureReader open(String url) {
    try {
      var req = http.request(url).GET().build();
      HttpResponse<InputStream> r = http.send(req);
      BufferedInputStream in = new BufferedInputStream(r.body(), 8192);
      in.mark(8192);
      byte[] head = in.readNBytes(4096);
      in.reset();
      String prefix = new String(head, StandardCharsets.UTF_8);
      if (r.statusCode() != 200 || prefix.contains("ExceptionReport")) {
        String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        in.close();
        if (body.contains("ExceptionReport")) {
          throw new JdalException("WFS server error: " + exceptionText(body));
        }
        throw new JdalException(
            "HTTP error code : " + r.statusCode() + (body.isBlank() ? "" : ", " + body.strip()));
      }
      return new GmlFeatureReader(in);
    } catch (IOException e) {
      throw new JdalException("Cannot read " + url + ": " + e.getMessage(), e);
    }
  }

  private static String exceptionText(String xml) {
    var m =
        java.util.regex.Pattern.compile(
                "<[^>]*ExceptionText[^>]*>(.*?)</", java.util.regex.Pattern.DOTALL)
            .matcher(xml);
    if (m.find()) return m.group(1).strip();
    var m2 =
        java.util.regex.Pattern.compile(
                "<[^>]*ServiceException[^>]*>(.*?)</", java.util.regex.Pattern.DOTALL)
            .matcher(xml);
    return m2.find() ? m2.group(1).strip() : xml.strip();
  }

  @Override
  public Set<Capability> capabilities() {
    return EnumSet.of(Capability.FAST_SPATIAL_FILTER);
  }

  @Override
  public Stream<Feature> features() {
    return features(Query.ALL);
  }

  @Override
  public Stream<Feature> features(Query query) {
    FeatureType t = type();
    Predicate<Feature> sql =
        query.where() == null ? null : SqlEngine.require().compileFilter(t, query.where());
    OgcFilter.Version fv = OgcFilter.Version.forWfs(settings.version());
    Envelope box = query.candidateBox();
    Envelope urlBox = box;
    String filter = "";
    boolean exact;
    if (query.spatial() != null && geometryProperty != null && info.defaultSrs() != null) {
      // the spatial predicate goes into the filter, which cannot be combined with BBOX
      List<String> conditions = new ArrayList<>();
      conditions.add(spatialCondition(query.spatial().op(), query.spatial().geometry(), fv));
      if (query.bbox() != null)
        conditions.add(spatialCondition(SpatialOp.INTERSECTS, rectangle(query.bbox()), fv));
      Translation attributes =
          query.where() == null ? Translation.NONE : OgcFilter.translate(query.where(), t, fv);
      if (attributes.present()) conditions.add(attributes.text());
      filter = filterParam(new Translation(OgcFilter.and(fv, conditions), true));
      urlBox = null;
      exact = query.filter() == null && (sql == null || attributes.complete());
    } else {
      Translation attributes = pushedFilter(query);
      if (attributes.present()) filter = filterParam(attributes);
      exact =
          query.filter() == null
              && query.spatial() == null
              && (sql == null || attributes.complete());
    }
    // a limit can go to the server only if it selects exactly what we would keep
    long limit = exact ? query.limit() : -1;
    Iterator<Feature> it = new Pages(t, urlBox, limit, filter, box);
    Stream<Feature> s =
        StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL),
                false)
            .onClose(((Pages) it)::close);
    // the whole query is applied again here: the service may ignore or only approximate parts of it
    if (query.bbox() != null || query.filter() != null || query.spatial() != null || sql != null)
      s = s.filter(f -> query.matches(f) && (sql == null || sql.test(f)));
    if (query.limit() >= 0) s = s.limit(query.limit());
    return s;
  }

  private String spatialCondition(SpatialOp op, dev.jdal.geom.Geometry g, OgcFilter.Version fv) {
    String gml =
        GmlGeometryWriter.write(
            g, GmlGeometryWriter.Version.forWfs(settings.version()), info.defaultSrs());
    return OgcFilter.spatial(fv, op, geometryProperty, gml);
  }

  private static dev.jdal.geom.Polygon rectangle(Envelope b) {
    return dev.jdal.geom.Polygon.of(
        dev.jdal.geom.CoordinateSequence.xy(
            b.minX(), b.minY(), b.maxX(), b.minY(), b.maxX(), b.maxY(), b.minX(), b.maxY(),
            b.minX(), b.minY()));
  }

  /**
   * The OGC filter for the query's attribute filter, if part of it can be sent. A KVP request
   * cannot have both BBOX and FILTER, so a spatial filter takes precedence.
   */
  private Translation pushedFilter(Query query) {
    if (query.where() == null || query.candidateBox() != null) return Translation.NONE;
    return OgcFilter.translate(query.where(), type(), OgcFilter.Version.forWfs(settings.version()));
  }

  private String filterParam(Translation t) {
    String xml = OgcFilter.filterElement(t.text(), OgcFilter.Version.forWfs(settings.version()));
    return "&FILTER=" + java.net.URLEncoder.encode(xml, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** The features of one or more GetFeature responses. */
  private final class Pages implements Iterator<Feature> {
    private final FeatureType t;
    private Envelope bbox;
    private final Envelope fallbackBox;
    private long limit;
    private String filter;
    private GmlFeatureReader reader;
    private long fid;
    private long start;
    private long inPage;
    private boolean done;
    private Feature next;

    Pages(FeatureType t, Envelope bbox, long limit, String filter, Envelope fallbackBox) {
      this.t = t;
      this.bbox = bbox;
      this.fallbackBox = fallbackBox;
      this.limit = limit;
      this.filter = filter;
    }

    private void openPage() {
      long pageSize = settings.paging() ? settings.pageSize() : -1;
      long count =
          limit >= 0 ? (pageSize >= 0 ? Math.min(limit - fid, pageSize) : limit) : pageSize;
      String url = getFeatureUrl(bbox, count, filter);
      if (settings.paging()) url = WfsDataset.append(url, "STARTINDEX=" + start);
      try {
        reader = open(url);
      } catch (JdalException e) {
        if (filter.isEmpty() || fid > 0 || start > 0) throw e;
        // the server does not take the filter: ask without it, the filter is applied here
        filter = "";
        limit = -1;
        bbox = fallbackBox;
        openPage();
        return;
      }
      inPage = 0;
    }

    private void advance() {
      while (next == null && !done) {
        if (reader == null) {
          if (limit >= 0 && fid >= limit) {
            done = true;
            break;
          }
          openPage();
        }
        if (reader.hasNext()) {
          GmlFeature f = reader.next();
          if (!f.typeName().equals(local())) continue;
          inPage++;
          next = GmlSchema.convert(t, f, fid++);
        } else {
          reader.close();
          reader = null;
          if (!settings.paging() || inPage < settings.pageSize() || inPage == 0) done = true;
          else start += inPage;
        }
      }
    }

    @Override
    public boolean hasNext() {
      advance();
      return next != null;
    }

    @Override
    public Feature next() {
      advance();
      if (next == null) throw new NoSuchElementException();
      Feature f = next;
      next = null;
      return f;
    }

    void close() {
      if (reader != null) reader.close();
      reader = null;
      done = true;
    }
  }

  @Override
  public long count(Query query) {
    if (query.where() != null
        && query.filter() == null
        && query.candidateBox() == null
        && query.limit() < 0) {
      Translation t = pushedFilter(query);
      if (t.present() && t.complete()) {
        Long hits = hits(null, filterParam(t));
        if (hits != null) return hits;
      }
    }
    if (query.isUnrestricted()
        || (query.bbox() != null
            && query.spatial() == null
            && query.filter() == null
            && query.where() == null
            && query.limit() < 0)) {
      Long hits = hits(query.bbox(), "");
      if (hits != null) return hits;
    }
    return Layer.super.count(query);
  }

  /**
   * The number of features according to {@code RESULTTYPE=hits}, or null if the server does not
   * say.
   */
  private Long hits(Envelope bbox, String filter) {
    String url =
        request(
            getFeatureUrl,
            "GetFeature",
            "&RESULTTYPE=hits" + (bbox != null ? "&BBOX=" + bboxParam(bbox) : "") + filter);
    try (GmlFeatureReader r = open(url)) {
      for (String attr : List.of("numberMatched", "numberOfFeatures")) {
        String v = r.rootAttribute(attr);
        if (v != null && v.matches("\\d+")) return Long.parseLong(v);
      }
    } catch (JdalException e) {
      return null;
    }
    return null;
  }

  @Override
  public Optional<Envelope> extent() {
    if (info.wgs84Bbox() != null) {
      SpatialReference srs = layerSrs();
      if (srs != null && srs.epsg() == 4326) return Optional.of(info.wgs84Bbox());
    }
    return Layer.super.extent();
  }
}
