package dev.jdal.driver.wfs;

import dev.jdal.core.Dataset;
import dev.jdal.core.Driver;
import dev.jdal.core.JdalException;
import dev.jdal.core.Layer;
import dev.jdal.core.Options;
import dev.jdal.http.Http;
import dev.jdal.vsi.Vsi;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The feature types of a WFS as layers. */
final class WfsDataset implements Dataset {

  private final WfsDriver driver;
  private final String location;
  private final List<Layer> layers = new ArrayList<>();

  WfsDataset(WfsDriver driver, String location, String target, Http http, Options options) {
    this.driver = driver;
    this.location = location;
    String capabilitiesXml;
    String endpoint;
    if (target.regionMatches(true, 0, "http", 0, 4)) {
      endpoint = withoutServiceParameters(target);
      String versionParam = options.has("VERSION") ? "&VERSION=" + options.get("VERSION") : "";
      capabilitiesXml =
          fetch(http, append(endpoint, "SERVICE=WFS" + versionParam + "&REQUEST=GetCapabilities"));
    } else {
      endpoint = null;
      try {
        capabilitiesXml = Vsi.global().readString(target);
      } catch (IOException e) {
        throw new JdalException("Cannot read " + target + ": " + e.getMessage(), e);
      }
    }
    WfsCapabilities caps = WfsCapabilities.parse(capabilitiesXml);
    String version = options.has("VERSION") ? options.get("VERSION") : caps.version();
    if (version == null || version.isEmpty()) version = "1.1.0";
    String base = caps.getFeatureUrl() != null ? caps.getFeatureUrl() : endpoint;
    String describeBase =
        caps.describeFeatureTypeUrl() != null ? caps.describeFeatureTypeUrl() : base;
    if (base == null)
      throw new JdalException(
          "The capabilities name no GetFeature URL; open the service URL instead");
    WfsLayer.Settings settings =
        new WfsLayer.Settings(
            version, options.bool("PAGING_ALLOWED", false), options.integer("PAGE_SIZE", 100));
    for (WfsCapabilities.FeatureTypeInfo ft : caps.featureTypes()) {
      layers.add(new WfsLayer(http, ft, base, describeBase, settings));
    }
  }

  /** Removes SERVICE, REQUEST and VERSION from a URL's query so that our own can be added. */
  static String withoutServiceParameters(String url) {
    int q = url.indexOf('?');
    if (q < 0) return url;
    List<String> kept = new ArrayList<>();
    for (String p : url.substring(q + 1).split("&")) {
      String key = p.contains("=") ? p.substring(0, p.indexOf('=')) : p;
      if (key.equalsIgnoreCase("SERVICE")
          || key.equalsIgnoreCase("REQUEST")
          || key.equalsIgnoreCase("VERSION")
          || key.equalsIgnoreCase("ACCEPTVERSIONS")) continue;
      if (!p.isEmpty()) kept.add(p);
    }
    return url.substring(0, q) + (kept.isEmpty() ? "" : "?" + String.join("&", kept));
  }

  /** Adds query parameters to a URL that may already have some. */
  static String append(String url, String params) {
    if (!url.contains("?")) return url + "?" + params;
    return url.endsWith("?") || url.endsWith("&") ? url + params : url + "&" + params;
  }

  static String fetch(Http http, String url) {
    try {
      Http.Response r = http.get(url, java.util.Map.of());
      if (!r.ok())
        throw new JdalException(
            "HTTP error code : "
                + r.status()
                + (r.body().length > 0 ? ", " + r.text().strip() : ""));
      return r.text();
    } catch (IOException e) {
      throw new JdalException("Cannot read " + url + ": " + e.getMessage(), e);
    }
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
