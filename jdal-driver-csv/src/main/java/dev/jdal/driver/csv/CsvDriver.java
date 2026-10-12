package dev.jdal.driver.csv;

import dev.jdal.core.*;
import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiPath;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** CSV files and directories of CSV files; "CSV:path" forces the driver for other extensions. */
public final class CsvDriver implements Driver {

  private static final Vsi VSI = Vsi.global();

  @Override
  public String name() {
    return "CSV";
  }

  @Override
  public String description() {
    return "Comma Separated Value (.csv)";
  }

  @Override
  public List<String> extensions() {
    return List.of("csv");
  }

  @Override
  public boolean identify(OpenInfo info) {
    String loc = info.location();
    if (loc.regionMatches(true, 0, "CSV:", 0, 4)) return true;
    if (info.isFile()) return CsvDataset.isCsvFile(loc);
    if (info.isDirectory()) {
      try {
        List<String> files =
            VSI.list(loc).stream().filter(n -> VSI.isFile(VsiPath.join(loc, n))).toList();
        long csv = files.stream().filter(CsvDataset::isCsvFile).count();
        return csv > 0 && csv * 2 >= files.size();
      } catch (IOException e) {
        return false;
      }
    }
    return false;
  }

  @Override
  public Dataset open(String location, boolean update, Map<String, String> options) {
    String loc = location.regionMatches(true, 0, "CSV:", 0, 4) ? location.substring(4) : location;
    String p = loc;
    Options o = Options.of(options);
    if (VSI.isDirectory(p)) {
      CsvDataset ds = new CsvDataset(this, location, p, false, update);
      for (String f : CsvDataset.csvFiles(p)) ds.add(openLayer(f, update, o));
      return ds;
    }
    if (!VSI.isFile(p)) throw new JdalException("No such file: " + loc);
    CsvDataset ds = new CsvDataset(this, location, p, true, update);
    ds.add(openLayer(p, update, o));
    return ds;
  }

  private static Layer openLayer(String file, boolean update, Options o) {
    String name = CsvSchema.stripExtension(VsiPath.fileName(file));
    return new CsvReadLayer(file, CsvSchema.infer(file, name, o), update);
  }

  @Override
  public boolean canCreate() {
    return true;
  }

  @Override
  public Dataset create(String location, Map<String, String> options) {
    String p = location;
    boolean file = CsvDataset.isCsvFile(p);
    try {
      if (!file) VSI.mkdirs(p);
    } catch (IOException e) {
      throw new JdalException("Cannot create " + location + ": " + e.getMessage(), e);
    }
    return new CsvDataset(this, location, p, file, true);
  }

  @Override
  public List<OptionDef> openOptions() {
    return List.of(
        OptionDef.bool("AUTODETECT_TYPE", false, "Detect field types from the data"),
        OptionDef.choice(
            "AUTODETECT_WIDTH", "NO", "Detect field widths", "NO", "YES", "STRING_ONLY"),
        OptionDef.string("AUTODETECT_SIZE_LIMIT", "Bytes to scan when detecting types"),
        OptionDef.choice(
            "HEADERS", "AUTO", "Whether the first line is a header", "AUTO", "YES", "NO"),
        OptionDef.string("SEPARATOR", "Field separator, a character or name"),
        OptionDef.bool("MERGE_SEPARATOR", false, "Merge consecutive separators"),
        OptionDef.bool("QUOTED_FIELDS_AS_STRING", false, "Quoted values are always strings"),
        OptionDef.bool("KEEP_GEOM_COLUMNS", true, "Keep the columns used to build the geometry"),
        OptionDef.string("GEOM_POSSIBLE_NAMES", "Columns holding WKT/WKB geometry"),
        OptionDef.string("X_POSSIBLE_NAMES", "Columns holding the X coordinate"),
        OptionDef.string("Y_POSSIBLE_NAMES", "Columns holding the Y coordinate"),
        OptionDef.string("Z_POSSIBLE_NAMES", "Columns holding the Z coordinate"));
  }

  @Override
  public List<OptionDef> layerCreationOptions() {
    return List.of(
        OptionDef.choice(
            "GEOMETRY",
            "NONE",
            "How to write geometries",
            "NONE",
            "AS_WKT",
            "AS_XY",
            "AS_XYZ",
            "AS_YX"),
        OptionDef.choice(
            "SEPARATOR", "COMMA", "Field separator", "COMMA", "SEMICOLON", "TAB", "SPACE", "PIPE"),
        OptionDef.choice(
            "STRING_QUOTING",
            "IF_AMBIGUOUS",
            "When to quote strings",
            "ALWAYS",
            "IF_AMBIGUOUS",
            "IF_NEEDED"),
        OptionDef.bool("CREATE_CSVT", false, "Write a .csvt file with the field types"),
        OptionDef.bool("WRITE_BOM", false, "Write a UTF-8 byte order mark"),
        OptionDef.bool("HEADER", true, "Write a header line"),
        OptionDef.choice("LINEFORMAT", null, "Line ending", "CRLF", "LF"));
  }
}
