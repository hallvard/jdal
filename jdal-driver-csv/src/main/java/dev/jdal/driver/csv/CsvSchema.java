package dev.jdal.driver.csv;

import dev.jdal.core.Diagnostics;
import dev.jdal.core.JdalException;
import dev.jdal.core.Options;
import dev.jdal.feature.*;
import dev.jdal.geom.Dimension;
import dev.jdal.geom.GeometryType;
import dev.jdal.srs.SpatialReference;
import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiPath;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How the columns of a CSV file map to fields and geometry, derived from header, .csvt and open
 * options.
 */
final class CsvSchema {

  private static final Vsi VSI = Vsi.global();

  final char separator;
  final boolean mergeSeparators;
  final boolean hasHeader;
  final FeatureType type;

  /** Role of each file column. */
  final List<CsvTypes.Role> roles;

  /** For FIELD columns the index into the feature type's fields, else -1. */
  final List<Integer> fieldIndex;

  /** Column index of the WKT/WKB geometry column or -1. */
  final int geomColumn;

  final int xColumn, yColumn, zColumn;
  final boolean quotedFieldsAsString;

  /** True if types were autodetected (mismatching values then yield a warning and no value). */
  final boolean autodetected;

  private CsvSchema(
      char separator,
      boolean mergeSeparators,
      boolean hasHeader,
      FeatureType type,
      List<CsvTypes.Role> roles,
      List<Integer> fieldIndex,
      int geomColumn,
      int x,
      int y,
      int z,
      boolean quotedFieldsAsString,
      boolean autodetected) {
    this.separator = separator;
    this.mergeSeparators = mergeSeparators;
    this.hasHeader = hasHeader;
    this.type = type;
    this.roles = roles;
    this.fieldIndex = fieldIndex;
    this.geomColumn = geomColumn;
    this.xColumn = x;
    this.yColumn = y;
    this.zColumn = z;
    this.quotedFieldsAsString = quotedFieldsAsString;
    this.autodetected = autodetected;
  }

  int columnCount() {
    return roles.size();
  }

  static char detectSeparator(String firstLine) {
    char[] candidates = {',', ';', '\t', '|', ' '};
    int[] counts = new int[candidates.length];
    boolean inQuotes = false;
    for (int i = 0; i < firstLine.length(); i++) {
      char c = firstLine.charAt(i);
      if (c == '"') inQuotes = !inQuotes;
      else if (!inQuotes) {
        for (int k = 0; k < candidates.length; k++) if (c == candidates[k]) counts[k]++;
      }
    }
    // a space is only a separator if nothing else occurs
    int best = -1;
    for (int k = 0; k < 4; k++)
      if (counts[k] > 0 && (best < 0 || counts[k] > counts[best])) best = k;
    if (best < 0) return counts[4] > 0 ? ' ' : ',';
    int others = 0;
    for (int k = 0; k < 4; k++) if (k != best && counts[k] > 0) others++;
    if (others > 0) {
      StringBuilder sb = new StringBuilder();
      for (int k = 0; k < 4; k++)
        if (k != best && counts[k] > 0)
          sb.append(sb.length() > 0 ? ", " : "").append(name(candidates[k]));
      Diagnostics.warn(
          "Selecting "
              + name(candidates[best])
              + " as separator. Found other candidate separator(s): "
              + sb
              + ". Use the SEPARATOR open option to select a specific one.");
    }
    return candidates[best];
  }

  private static String name(char c) {
    return switch (c) {
      case ',' -> "comma";
      case ';' -> "semicolon";
      case '\t' -> "tab";
      case '|' -> "pipe";
      default -> "space";
    };
  }

  static char separatorFor(String option) {
    return switch (option.toUpperCase(Locale.ROOT)) {
      case "COMMA" -> ',';
      case "SEMICOLON" -> ';';
      case "TAB" -> '\t';
      case "PIPE" -> '|';
      case "SPACE" -> ' ';
      default -> throw new JdalException("Invalid SEPARATOR '" + option + "'");
    };
  }

  private static String firstLine(String file) {
    try (Reader r = VSI.newReader(file)) {
      StringBuilder sb = new StringBuilder();
      int c;
      boolean inQuotes = false;
      boolean first = true;
      while ((c = r.read()) >= 0) {
        if (first) {
          first = false;
          if (c == '﻿') continue;
        }
        if (c == '"') inQuotes = !inQuotes;
        if (!inQuotes && (c == '\n' || c == '\r')) break;
        sb.append((char) c);
      }
      return sb.toString();
    } catch (IOException e) {
      throw new JdalException("Cannot read " + file + ": " + e.getMessage(), e);
    }
  }

  static boolean globMatches(String glob, String name) {
    String re = Pattern.quote(glob.toLowerCase(Locale.ROOT)).replace("*", "\\E.*\\Q");
    return name.toLowerCase(Locale.ROOT).matches(re);
  }

  private static int findColumn(String namesOption, List<String> names, List<CsvTypes.Role> taken) {
    if (namesOption == null) return -1;
    for (String glob : namesOption.split(",")) {
      for (int i = 0; i < names.size(); i++) {
        if (taken.get(i) == CsvTypes.Role.FIELD && globMatches(glob.strip(), names.get(i)))
          return i;
      }
    }
    return -1;
  }

  static CsvSchema infer(String file, String layerName, Options o) {
    String line1 = firstLine(file);
    char sep =
        o.has("SEPARATOR") && !o.get("SEPARATOR").equalsIgnoreCase("AUTO")
            ? separatorFor(o.get("SEPARATOR"))
            : detectSeparator(line1);
    boolean merge = o.bool("MERGE_SEPARATOR", false);

    CsvRecords.Record header;
    try (CsvRecords r = new CsvRecords(VSI.newReader(file), sep, merge)) {
      header = r.next();
    } catch (IOException e) {
      throw new JdalException("Cannot read " + file + ": " + e.getMessage(), e);
    }
    if (header == null) throw new JdalException("Empty CSV file " + file);
    int n = header.fields().size();

    String headers = o.choice("HEADERS", "AUTO", "AUTO", "YES", "NO");
    boolean hasHeader;
    if (headers.equals("AUTO")) {
      hasHeader = true;
      for (int i = 0; i < n; i++) {
        String f = header.fields().get(i).strip();
        if (!header.quoted().get(i) && !f.isEmpty() && isNumber(f)) hasHeader = false;
      }
    } else {
      hasHeader = headers.equals("YES");
    }
    List<String> names = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      String f = hasHeader ? header.fields().get(i) : "";
      names.add(f.isEmpty() ? "field_" + (i + 1) : f);
    }

    List<CsvTypes.Role> roles = new ArrayList<>();
    List<FieldDefn> defs = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      roles.add(CsvTypes.Role.FIELD);
      defs.add(FieldDefn.of(names.get(i), FieldType.STRING));
    }

    // .csvt
    boolean typed = false;
    String csvt = VsiPath.withExtension(file, "csvt");
    if (VSI.isFile(csvt)) {
      try {
        String text = VSI.readString(csvt).lines().findFirst().orElse("");
        try (CsvRecords r = new CsvRecords(new java.io.StringReader(text), ',', false)) {
          CsvRecords.Record rec = r.next();
          if (rec != null) {
            for (int i = 0; i < Math.min(n, rec.fields().size()); i++) {
              CsvTypes.Column c = CsvTypes.parseCsvt(names.get(i), rec.fields().get(i));
              if (c != null) {
                roles.set(i, c.role());
                defs.set(i, c.defn());
              }
            }
            typed = true;
          }
        }
      } catch (IOException e) {
        throw new JdalException("Cannot read " + csvt + ": " + e.getMessage(), e);
      }
    }

    boolean quotedAsString = o.bool("QUOTED_FIELDS_AS_STRING", false);
    boolean autodetect = !typed && o.bool("AUTODETECT_TYPE", false);
    if (autodetect) autodetectTypes(file, sep, merge, hasHeader, n, defs, o, quotedAsString);

    // geometry columns
    int geom = -1, x = -1, y = -1, z = -1;
    for (int i = 0; i < n; i++) {
      if (roles.get(i) == CsvTypes.Role.WKT && geom < 0) geom = i;
      if (roles.get(i) == CsvTypes.Role.X && x < 0) x = i;
      if (roles.get(i) == CsvTypes.Role.Y && y < 0) y = i;
    }
    List<String> nameList = names;
    if (geom < 0 && o.has("GEOM_POSSIBLE_NAMES"))
      geom = findColumn(o.get("GEOM_POSSIBLE_NAMES"), nameList, roles);
    if (geom < 0 && !o.has("GEOM_POSSIBLE_NAMES")) {
      for (int i = 0; i < n; i++)
        if (names.get(i).equals("WKT") && roles.get(i) == CsvTypes.Role.FIELD) geom = i;
    }
    if (x < 0 && y < 0) {
      x = findColumn(o.get("X_POSSIBLE_NAMES"), nameList, roles);
      y = findColumn(o.get("Y_POSSIBLE_NAMES"), nameList, roles);
      z = findColumn(o.get("Z_POSSIBLE_NAMES"), nameList, roles);
      if (x < 0 || y < 0) {
        x = -1;
        y = -1;
        z = -1;
      }
    }
    boolean keepGeom = o.bool("KEEP_GEOM_COLUMNS", true);
    if (geom >= 0 && roles.get(geom) == CsvTypes.Role.FIELD) roles.set(geom, CsvTypes.Role.WKT);
    if (x >= 0 && y >= 0) {
      roles.set(x, CsvTypes.Role.X);
      roles.set(y, CsvTypes.Role.Y);
    }

    List<FieldDefn> fields = new ArrayList<>();
    List<Integer> fieldIndex = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      CsvTypes.Role r = roles.get(i);
      boolean keep =
          switch (r) {
            case FIELD -> i != z || keepGeom;
            case WKT -> true; // a WKT column is also a regular field
            case X, Y -> keepGeom;
          };
      if (i == z && !keepGeom) keep = false;
      if (keep) {
        fieldIndex.add(fields.size());
        fields.add(defs.get(i));
      } else {
        fieldIndex.add(-1);
      }
    }
    boolean hasXY = x >= 0 && y >= 0;
    GeometryType gt =
        hasXY ? GeometryType.POINT : geom >= 0 ? GeometryType.UNKNOWN : GeometryType.NONE;
    SpatialReference srs = readPrj(file);
    List<GeomFieldDefn> geoms =
        gt == GeometryType.NONE
            ? List.of()
            : List.of(
                new GeomFieldDefn(
                    "", gt, hasXY && z >= 0 ? Dimension.XYZ : Dimension.XY, srs, true));
    return new CsvSchema(
        sep,
        merge,
        hasHeader,
        new FeatureType(layerName, fields, geoms),
        roles,
        fieldIndex,
        geom,
        hasXY ? x : -1,
        hasXY ? y : -1,
        hasXY ? z : -1,
        quotedAsString,
        autodetect);
  }

  private static SpatialReference readPrj(String file) {
    String prj = VsiPath.withExtension(file, "prj");
    if (!VSI.isFile(prj)) return null;
    try {
      return SpatialReference.fromWkt(VSI.readString(prj));
    } catch (IOException | IllegalArgumentException e) {
      return null;
    }
  }

  static String stripExtension(String name) {
    int i = name.lastIndexOf('.');
    return i < 0 ? name : name.substring(0, i);
  }

  private static boolean isNumber(String s) {
    try {
      Double.parseDouble(s);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  // ------------------------------------------------------------------ type autodetection

  private static void autodetectTypes(
      String file,
      char sep,
      boolean merge,
      boolean hasHeader,
      int n,
      List<FieldDefn> defs,
      Options o,
      boolean quotedAsString) {
    long limit =
        o.has("AUTODETECT_SIZE_LIMIT")
            ? Long.parseLong(o.get("AUTODETECT_SIZE_LIMIT"))
            : 1_000_000L;
    String widthOpt = o.choice("AUTODETECT_WIDTH", "NO", "NO", "YES", "STRING_ONLY");
    CsvTypes.Kind[] kinds = new CsvTypes.Kind[n];
    java.util.Arrays.fill(kinds, CsvTypes.Kind.EMPTY);
    int[] width = new int[n], precision = new int[n];
    try (CsvRecords r = new CsvRecords(VSI.newReader(file), sep, merge)) {
      CsvRecords.Record rec;
      boolean skipHeader = hasHeader;
      while ((rec = r.next()) != null) {
        if (skipHeader) {
          skipHeader = false;
          continue;
        }
        if (limit > 0 && rec.endOffset() > limit) break;
        for (int i = 0; i < Math.min(n, rec.fields().size()); i++) {
          String v = rec.fields().get(i);
          if (v.isEmpty()) continue;
          CsvTypes.Kind k =
              quotedAsString && rec.quoted().get(i) ? CsvTypes.Kind.STRING : CsvTypes.classify(v);
          kinds[i] = CsvTypes.merge(kinds[i], k);
          width[i] = Math.max(width[i], v.length());
          int dot = v.indexOf('.');
          if (dot >= 0 && k == CsvTypes.Kind.REAL)
            precision[i] = Math.max(precision[i], v.length() - dot - 1);
        }
      }
    } catch (IOException e) {
      throw new JdalException("Cannot read " + file + ": " + e.getMessage(), e);
    }
    for (int i = 0; i < n; i++) {
      FieldDefn d = defs.get(i);
      CsvTypes.Kind k = kinds[i];
      FieldDefn nd =
          switch (k) {
            case EMPTY, STRING -> d;
            case BOOL -> FieldDefn.of(d.name(), FieldType.INTEGER, FieldSubType.BOOLEAN);
            case INT -> FieldDefn.of(d.name(), FieldType.INTEGER);
            case INT64 -> FieldDefn.of(d.name(), FieldType.INTEGER64);
            case REAL -> FieldDefn.of(d.name(), FieldType.REAL);
            case DATE -> FieldDefn.of(d.name(), FieldType.DATE);
            case TIME -> FieldDefn.of(d.name(), FieldType.TIME);
            case DATETIME -> FieldDefn.of(d.name(), FieldType.DATETIME);
          };
      boolean wantWidth =
          widthOpt.equals("YES")
              || (widthOpt.equals("STRING_ONLY") && nd.type() == FieldType.STRING);
      if (wantWidth
          && k != CsvTypes.Kind.EMPTY
          && !(k == CsvTypes.Kind.DATE || k == CsvTypes.Kind.TIME || k == CsvTypes.Kind.DATETIME)) {
        nd = nd.withWidth(width[i], nd.type() == FieldType.REAL ? precision[i] : 0);
      }
      defs.set(i, nd);
    }
  }
}
