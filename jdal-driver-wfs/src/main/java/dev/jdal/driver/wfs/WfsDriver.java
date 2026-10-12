package dev.jdal.driver.wfs;

import dev.jdal.core.Dataset;
import dev.jdal.core.Driver;
import dev.jdal.core.JdalException;
import dev.jdal.core.OpenInfo;
import dev.jdal.core.OptionDef;
import dev.jdal.core.Options;
import dev.jdal.http.Http;
import java.util.List;
import java.util.Map;

/**
 * OGC Web Feature Service (1.0, 1.1 and 2.0) as a read-only dataset: {@code WFS:http://host/wfs}
 * (or a URL with {@code SERVICE=WFS}), or {@code WFS:path/to/capabilities.xml}. Every feature type
 * is a layer; features come as GML.
 */
public final class WfsDriver implements Driver {

  @Override
  public String name() {
    return "WFS";
  }

  @Override
  public String description() {
    return "OGC WFS (Web Feature Service)";
  }

  @Override
  public boolean identify(OpenInfo info) {
    String l = info.location().strip();
    if (l.regionMatches(true, 0, "WFS:", 0, 4)) return true;
    return l.regionMatches(true, 0, "http", 0, 4) && l.toUpperCase().contains("SERVICE=WFS");
  }

  @Override
  public Dataset open(String location, boolean update, Map<String, String> options) {
    if (update) throw new JdalException("WFS datasets are read-only");
    String l = location.strip();
    if (l.regionMatches(true, 0, "WFS:", 0, 4)) l = l.substring(4);
    return new WfsDataset(this, location, l, Http.global(), Options.of(options));
  }

  @Override
  public List<OptionDef> openOptions() {
    return List.of(
        OptionDef.string(
            "VERSION", "WFS version to request, e.g. 1.1.0; default: what the server offers"),
        OptionDef.bool(
            "PAGING_ALLOWED", false, "Fetch features in pages with STARTINDEX and COUNT"),
        OptionDef.string("PAGE_SIZE", "100", "Features per page when paging"));
  }
}
