package dev.jdal.driver.shapefile;

import dev.jdal.core.*;
import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiPath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** ESRI Shapefile: .shp files, .dbf tables without geometry, and directories of them. */
public final class ShapefileDriver implements Driver {

  @Override
  public String name() {
    return "ESRI Shapefile";
  }

  @Override
  public String description() {
    return "ESRI Shapefile";
  }

  @Override
  public List<String> extensions() {
    return List.of("shp", "dbf");
  }

  private static String ext(String p) {
    return VsiPath.extension(p).toLowerCase(Locale.ROOT);
  }

  @Override
  public boolean identify(OpenInfo info) {
    if (info.isFile()) {
      String e = info.extension();
      byte[] h = info.header();
      if (e.equals("shp"))
        return h.length >= 4 && h[0] == 0 && h[1] == 0 && h[2] == 0x27 && (h[3] & 0xFF) == 0x0A;
      if (e.equals("dbf"))
        return h.length >= 32
            && (h[0] == 0x03 || h[0] == (byte) 0x83 || h[0] == 0x30 || h[0] == 0x31);
      return false;
    }
    if (info.isDirectory()) return !layerFiles(info.location()).isEmpty();
    return false;
  }

  /** .shp files and .dbf files that have no .shp, sorted by name. */
  static List<String> layerFiles(String dir) {
    Vsi vsi = Vsi.global();
    try {
      List<String> all =
          vsi.list(dir).stream().map(n -> VsiPath.join(dir, n)).filter(vsi::isFile).toList();
      Set<String> shpBases = new TreeSet<>();
      for (String p : all)
        if (ext(p).equals("shp")) shpBases.add(DbfFile.base(p).toLowerCase(Locale.ROOT));
      List<String> out = new ArrayList<>();
      for (String p : all) {
        String e = ext(p);
        if (e.equals("shp")
            || (e.equals("dbf") && !shpBases.contains(DbfFile.base(p).toLowerCase(Locale.ROOT))))
          out.add(p);
      }
      out.sort(java.util.Comparator.comparing(p -> VsiPath.fileName(p).toLowerCase(Locale.ROOT)));
      return out;
    } catch (IOException e) {
      return List.of();
    }
  }

  @Override
  public Dataset open(String location, boolean update, Map<String, String> options) {
    String p = location;
    Vsi vsi = Vsi.global();
    Options o = Options.of(options);
    if (vsi.isDirectory(p)) {
      ShapefileDataset ds = new ShapefileDataset(this, location, p, false, update);
      for (String f : layerFiles(p)) ds.add(ShapefileLayer.open(f, update, o));
      return ds;
    }
    if (!vsi.isFile(p)) throw new JdalException("No such file: " + location);
    ShapefileDataset ds = new ShapefileDataset(this, location, p, true, update);
    ds.add(ShapefileLayer.open(p, update, o));
    return ds;
  }

  @Override
  public boolean canCreate() {
    return true;
  }

  @Override
  public Dataset create(String location, Map<String, String> options) {
    String p = location;
    String e = ext(p);
    boolean single = e.equals("shp") || e.equals("dbf");
    try {
      if (single) {
        Vsi.global().mkdirs(VsiPath.parent(p));
      } else {
        Vsi.global().mkdirs(p);
      }
    } catch (IOException ex) {
      throw new JdalException("Cannot create " + location + ": " + ex.getMessage(), ex);
    }
    return new ShapefileDataset(this, location, p, single, true);
  }

  @Override
  public List<OptionDef> openOptions() {
    return List.of(OptionDef.bool("PROMOTE_TO_MULTI", true, "Promote single to multi geometries"));
  }

  @Override
  public List<OptionDef> layerCreationOptions() {
    return List.of(
        OptionDef.choice(
            "SHPT",
            null,
            "Shape type",
            "POINT",
            "ARC",
            "POLYGON",
            "MULTIPOINT",
            "POINTZ",
            "ARCZ",
            "POLYGONZ",
            "MULTIPOINTZ",
            "POINTM",
            "ARCM",
            "POLYGONM",
            "MULTIPOINTM"),
        OptionDef.string("ENCODING", "LDID/87", "DBF code page, e.g. UTF-8 or LDID/87"),
        OptionDef.bool("OVERWRITE", false, "Replace an existing shapefile"),
        OptionDef.bool("PROMOTE_TO_MULTI", true, "Write single geometries as multi"));
  }
}
