package dev.jdal.driver.geojson;

import dev.jdal.core.Dataset;
import dev.jdal.core.Driver;
import dev.jdal.core.OpenInfo;
import dev.jdal.vsi.VsiPath;
import java.util.List;
import java.util.Map;

public final class GeoJsonDriver implements Driver {

  static final GeoJsonDriver INSTANCE = new GeoJsonDriver();

  @Override
  public String name() {
    return "GeoJSON";
  }

  @Override
  public String description() {
    return "GeoJSON";
  }

  @Override
  public List<String> extensions() {
    return List.of("geojson", "json");
  }

  @Override
  public boolean identify(OpenInfo info) {
    if (isInlineText(info.location())) return true;
    if (!info.isFile()) return false;
    String ext = info.extension();
    if (!ext.equals("geojson") && !ext.equals("json")) return false;
    String h = new String(info.header(), java.nio.charset.StandardCharsets.UTF_8);
    if (ext.equals("geojson")) return h.contains("{");
    return h.contains("\"FeatureCollection\"")
        || h.contains("\"coordinates\"")
        || h.contains("\"Feature\"");
  }

  private static boolean isInlineText(String location) {
    return location.stripLeading().startsWith("{") && location.contains("\"type\"");
  }

  @Override
  public Dataset open(String location, boolean update, Map<String, String> options) {
    if (isInlineText(location)) {
      return readDataset("OGRGeoJSON", GeoJsonSource.ofText(location), "OGRGeoJSON");
    }
    String base = VsiPath.fileName(location);
    int dot = base.lastIndexOf('.');
    return readDataset(
        location, GeoJsonSource.ofFile(location), dot > 0 ? base.substring(0, dot) : base);
  }

  private Dataset readDataset(String name, GeoJsonSource source, String defaultLayerName) {
    return new GeoJsonDataset(
        this, name, new GeoJsonReadLayer(source, GeoJsonSchema.scan(source, defaultLayerName)));
  }

  @Override
  public boolean canCreate() {
    return true;
  }

  @Override
  public Dataset create(String location, Map<String, String> options) {
    return new GeoJsonDataset(this, location);
  }
}
