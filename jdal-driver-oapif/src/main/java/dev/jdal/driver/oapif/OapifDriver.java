package dev.jdal.driver.oapif;

import dev.jdal.core.Dataset;
import dev.jdal.core.Driver;
import dev.jdal.core.JdalException;
import dev.jdal.core.OpenInfo;
import dev.jdal.core.OptionDef;
import dev.jdal.http.Http;
import java.util.List;
import java.util.Map;

/**
 * OGC API - Features: {@code OAPIF:http://server/api} lists the collections as layers; a URL that
 * ends in {@code /collections/name} opens that collection only. {@code WFS3:} is the old prefix.
 * Read only; bounding box and limit are sent to the server.
 */
public final class OapifDriver implements Driver {

  @Override
  public String name() {
    return "OAPIF";
  }

  @Override
  public String description() {
    return "OGC API - Features";
  }

  @Override
  public boolean identify(OpenInfo info) {
    return prefixLength(info.location()) > 0;
  }

  static int prefixLength(String location) {
    if (location.regionMatches(true, 0, "OAPIF:", 0, 6)) return 6;
    if (location.regionMatches(true, 0, "WFS3:", 0, 5)) return 5;
    return 0;
  }

  @Override
  public Dataset open(String location, boolean update, Map<String, String> options) {
    if (update) throw new JdalException("OAPIF datasets are read-only");
    int p = prefixLength(location);
    String url = location.substring(p).strip();
    if (!url.regionMatches(true, 0, "http", 0, 4)) {
      throw new JdalException("OAPIF needs an http(s) URL: " + location);
    }
    return new OapifDataset(this, location, url, Http.global(), options);
  }

  @Override
  public List<OptionDef> openOptions() {
    return List.of(
        OptionDef.string("PAGE_SIZE", "1000", "Number of features requested per page"),
        OptionDef.string(
            "INITIAL_REQUEST_PAGE_SIZE", "20", "Number of features requested to find the schema"),
        OptionDef.string("DATETIME", "Value of the datetime parameter, an instant or an interval"));
  }
}
