package dev.jdal.driver.csv;

import dev.jdal.core.Capability;
import dev.jdal.core.Diagnostics;
import dev.jdal.core.JdalException;
import dev.jdal.core.Layer;
import dev.jdal.feature.*;
import dev.jdal.geom.*;
import dev.jdal.vsi.Vsi;
import java.io.IOException;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A CSV file read sequentially; every scan reads the file again. Appending is supported in update
 * mode.
 */
final class CsvReadLayer implements Layer {

  private static final Vsi VSI = Vsi.global();

  private final String file;
  private final CsvSchema schema;
  private final boolean writable;
  private CsvAppender appender;

  CsvReadLayer(String file, CsvSchema schema, boolean writable) {
    this.file = file;
    this.schema = schema;
    this.writable = writable;
  }

  @Override
  public String name() {
    return schema.type.name();
  }

  @Override
  public FeatureType type() {
    return schema.type;
  }

  @Override
  public Set<Capability> capabilities() {
    return writable
        ? EnumSet.of(Capability.SEQUENTIAL_WRITE, Capability.CREATE_FIELD)
        : EnumSet.noneOf(Capability.class);
  }

  @Override
  public Stream<Feature> features() {
    sync();
    CsvRecords records;
    try {
      records = new CsvRecords(VSI.newReader(file), schema.separator, schema.mergeSeparators);
    } catch (IOException e) {
      throw new JdalException("Cannot read " + file + ": " + e.getMessage(), e);
    }
    if (schema.hasHeader) records.next();
    Iterator<Feature> it =
        new Iterator<>() {
          Feature pending;
          long fid = 1;

          @Override
          public boolean hasNext() {
            if (pending == null) {
              CsvRecords.Record rec = records.next();
              if (rec != null) pending = toFeature(rec, fid++);
            }
            return pending != null;
          }

          @Override
          public Feature next() {
            if (!hasNext()) throw new NoSuchElementException();
            Feature f = pending;
            pending = null;
            return f;
          }
        };
    return StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL),
            false)
        .onClose(records::close);
  }

  private Feature toFeature(CsvRecords.Record rec, long fid) {
    FeatureType t = schema.type;
    Feature.Builder b = Feature.builder(t).fid(fid);
    List<String> cells = rec.fields();
    int n = schema.columnCount();
    double x = Double.NaN, y = Double.NaN, z = Double.NaN;
    boolean haveX = false, haveY = false, haveZ = false;
    for (int i = 0; i < Math.min(n, cells.size()); i++) {
      String v = cells.get(i);
      int fi = schema.fieldIndex.get(i);
      if (fi >= 0) {
        FieldDefn d = t.fields().get(fi);
        Object value = parseValue(d, v, rec.quoted().get(i), fid);
        if (value != null) b.set(fi, value);
      }
      if (i == schema.xColumn && !v.isBlank()) {
        x = parseCoordinate(v);
        haveX = true;
      } else if (i == schema.yColumn && !v.isBlank()) {
        y = parseCoordinate(v);
        haveY = true;
      } else if (i == schema.zColumn && !v.isBlank()) {
        z = parseCoordinate(v);
        haveZ = true;
      }
    }
    if (!t.geomFields().isEmpty()) {
      Geometry g = null;
      if (schema.xColumn >= 0) {
        if (haveX && haveY && !Double.isNaN(x) && !Double.isNaN(y))
          g = haveZ ? Point.xyz(x, y, z) : Point.xy(x, y);
      } else if (schema.geomColumn >= 0 && schema.geomColumn < cells.size()) {
        g = parseGeometry(cells.get(schema.geomColumn), fid);
      }
      b.geometry(g);
    }
    return b.build();
  }

  private static double parseCoordinate(String v) {
    try {
      return CsvTypes.parseDouble(v);
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  private Geometry parseGeometry(String text, long fid) {
    String t = text.strip();
    if (t.isEmpty()) return null;
    try {
      if (t.length() % 2 == 0
          && (t.startsWith("00") || t.startsWith("01"))
          && t.matches("[0-9A-Fa-f]+")) {
        return Wkb.read(HexFormat.of().parseHex(t));
      }
      return Wkt.read(t);
    } catch (IllegalArgumentException e) {
      Diagnostics.warn("Invalid geometry in record " + fid + ": " + e.getMessage());
      return null;
    }
  }

  private Object parseValue(FieldDefn d, String v, boolean quoted, long fid) {
    if (d.type() == FieldType.STRING) return v; // an empty string is a value
    if (v.isEmpty()) return null;
    try {
      if (d.subType() == FieldSubType.BOOLEAN && CsvTypes.isBooleanWord(v.strip())) {
        return CsvTypes.booleanWordValue(v.strip()) ? 1 : 0;
      }
      return switch (d.type()) {
        case INTEGER, INTEGER64 -> {
          String s = v.strip();
          yield FieldValues.coerce(d, s.startsWith("+") ? s.substring(1) : s);
        }
        case REAL -> FieldValues.coerce(d, CsvTypes.parseDouble(v));
        case INTEGER_LIST, INTEGER64_LIST, REAL_LIST, STRING_LIST -> parseJsonList(d, v);
        default -> FieldValues.coerce(d, v);
      };
    } catch (IllegalArgumentException | DateTimeParseException e) {
      if (schema.autodetected) {
        Diagnostics.warn(
            "Value '"
                + v
                + "' of field "
                + d.name()
                + " of record "
                + fid
                + " cannot be parsed as "
                + d.type().displayName()
                + " as detected from the first records");
      } else {
        Diagnostics.warn("Invalid value '" + v + "' for field " + d.name() + " in record " + fid);
      }
      return null;
    }
  }

  private static Object parseJsonList(FieldDefn d, String v) {
    dev.jdal.json.JsonValue j = dev.jdal.json.Json.parse(v);
    if (!(j instanceof dev.jdal.json.JsonArray a))
      throw new IllegalArgumentException("not a JSON array");
    List<Object> out = new ArrayList<>();
    for (dev.jdal.json.JsonValue e : a) {
      out.add(
          e instanceof dev.jdal.json.JsonNumber n
              ? n.toNumber()
              : e instanceof dev.jdal.json.JsonString s ? s.value() : e.toString());
    }
    return out;
  }

  // ------------------------------------------------------------------ append

  @Override
  public synchronized Feature insert(Feature f) {
    if (!writable) throw new JdalException("CSV file opened read-only");
    if (appender == null) appender = CsvAppender.forExisting(file, schema);
    appender.write(f);
    return f;
  }

  @Override
  public synchronized void sync() {
    if (appender != null) appender.flush();
  }

  @Override
  public synchronized void close() {
    if (appender != null) {
      appender.close();
      appender = null;
    }
  }
}
