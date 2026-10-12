package dev.jdal.gml;

import dev.jdal.core.Dataset;
import dev.jdal.core.Driver;
import dev.jdal.core.JdalException;
import dev.jdal.core.OpenInfo;
import dev.jdal.core.OptionDef;
import dev.jdal.core.Options;
import java.util.List;
import java.util.Map;

/** GML files: feature collections in GML 2, 3.1 and 3.2, read only. */
public final class GmlDriver implements Driver {

  @Override
  public String name() {
    return "GML";
  }

  @Override
  public String description() {
    return "Geography Markup Language (GML)";
  }

  @Override
  public List<String> extensions() {
    return List.of("gml");
  }

  @Override
  public boolean identify(OpenInfo info) {
    if (!info.isFile()) return false;
    String ext = info.extension();
    String h = new String(info.header(), java.nio.charset.StandardCharsets.UTF_8);
    if (!h.contains("<")) return false;
    if (ext.equals("gml")) return true;
    return h.contains("opengis.net/gml")
        || h.contains("<gml:")
        || h.contains("<wfs:FeatureCollection");
  }

  @Override
  public Dataset open(String location, boolean update, Map<String, String> options) {
    if (update) throw new JdalException("GML datasets are read-only");
    return new GmlDataset(this, location, Options.of(options));
  }

  @Override
  public List<OptionDef> openOptions() {
    return List.of(
        OptionDef.bool("EXPOSE_GML_ID", true, "Expose gml:id (or fid) as a string field"),
        OptionDef.bool(
            "USE_SCHEMA", true, "Use the .xsd next to the file or named in xsi:schemaLocation"));
  }
}
