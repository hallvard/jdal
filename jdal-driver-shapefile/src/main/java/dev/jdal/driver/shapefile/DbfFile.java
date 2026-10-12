package dev.jdal.driver.shapefile;

import dev.jdal.core.Diagnostics;
import dev.jdal.core.JdalException;
import dev.jdal.feature.FieldDefn;
import dev.jdal.feature.FieldSubType;
import dev.jdal.feature.FieldType;
import dev.jdal.feature.FieldValues;
import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiPath;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A dBASE III table: header, field descriptors and fixed width records. */
final class DbfFile implements AutoCloseable {

  /** Column descriptor. */
  record Column(String name, char type, int length, int decimals, int offset) {}

  private final String path;
  private final BlockFile file;
  private int recordCount;
  private int headerLength;
  private int recordLength;
  private int ldid;
  private final List<Column> columns = new ArrayList<>();
  private Charset charset;
  private final byte[] updated = new byte[3];

  private DbfFile(String path, BlockFile file) {
    this.path = path;
    this.file = file;
  }

  // ------------------------------------------------------------------ open / create

  static DbfFile open(String path, boolean write) {
    DbfFile d = new DbfFile(path, BlockFile.open(path, write));
    d.readHeader();
    d.charset = detectCharset(path, d.ldid);
    return d;
  }

  /**
   * Creates an empty table; {@code charset} is used for text, {@code ldid} written to the header.
   */
  static DbfFile create(String path, List<Column> cols, Charset charset, int ldid) {
    DbfFile d = new DbfFile(path, BlockFile.create(path));
    int off = 1;
    for (Column c : cols) {
      d.columns.add(new Column(c.name(), c.type(), c.length(), c.decimals(), off));
      off += c.length();
    }
    d.recordLength = off;
    d.headerLength = 32 + 32 * cols.size() + 1;
    d.recordCount = 0;
    d.charset = charset;
    d.ldid = ldid;
    d.writeHeader();
    return d;
  }

  private void readHeader() {
    ByteBuffer h = file.read(0, 32);
    if (h.remaining() < 32) throw new JdalException("Not a DBF file: " + path);
    int version = h.get(0) & 0xFF;
    if (version != 0x03
        && version != 0x83
        && version != 0x30
        && version != 0x04
        && version != 0x8B
        && version != 0xF5
        && version != 0x8E
        && version != 0x31
        && version != 0x32
        && version != 0xCB
        && version != 0x43
        && version != 0x63) {
      throw new JdalException("Not a DBF file: " + path);
    }
    recordCount = h.getInt(4);
    headerLength = h.getShort(8) & 0xFFFF;
    recordLength = h.getShort(10) & 0xFFFF;
    ldid = h.get(29) & 0xFF;
    h.get(1, updated);
    if (headerLength < 33 || recordLength < 1)
      throw new JdalException("Corrupt DBF header: " + path);
    int n = (headerLength - 33) / 32;
    ByteBuffer d = file.read(32, n * 32);
    int off = 1;
    for (int i = 0; i < n; i++) {
      int base = i * 32;
      if (base + 32 > d.limit() || (d.get(base) & 0xFF) == 0x0D) break;
      byte[] nameBytes = new byte[11];
      d.get(base, nameBytes);
      int len = 0;
      while (len < 11 && nameBytes[len] != 0) len++;
      String name = new String(nameBytes, 0, len, StandardCharsets.ISO_8859_1).strip();
      char type = (char) (d.get(base + 11) & 0xFF);
      int length = d.get(base + 16) & 0xFF;
      int dec = d.get(base + 17) & 0xFF;
      columns.add(new Column(name, type, length, dec, off));
      off += length;
    }
    if (off > recordLength)
      throw new JdalException("Corrupt DBF header (field widths exceed record length): " + path);
  }

  private static Charset detectCharset(String dbf, int ldid) {
    String cpg = VsiPath.sibling(dbf, base(dbf) + ".cpg");
    Vsi vsi = Vsi.global();
    if (vsi.isFile(cpg)) {
      try {
        String t = vsi.readString(cpg, StandardCharsets.ISO_8859_1).strip();
        Charset cs = charsetNamed(t);
        if (cs != null) return cs;
      } catch (java.io.IOException e) {
        // fall through to LDID
      }
    }
    return switch (ldid) {
      case 0x01 -> Charset.forName("IBM437");
      case 0x02 -> Charset.forName("IBM850");
      case 0x03, 0x57 -> StandardCharsets.ISO_8859_1; // GDAL: LDID/87 means ISO-8859-1
      case 0x08 -> Charset.forName("IBM865");
      case 0x0A -> Charset.forName("IBM850");
      case 0x4D, 0x4E, 0x4F, 0x50 -> Charset.forName("GBK");
      case 0x64 -> Charset.forName("IBM852");
      case 0x65 -> Charset.forName("IBM866");
      case 0x66 -> Charset.forName("IBM865");
      case 0xC8 -> Charset.forName("windows-1250");
      case 0xC9 -> Charset.forName("windows-1251");
      case 0xCB -> Charset.forName("windows-1253");
      case 0xCC -> Charset.forName("windows-1257");
      default -> StandardCharsets.ISO_8859_1;
    };
  }

  static Charset charsetNamed(String t) {
    String n = t.strip();
    if (n.isEmpty()) return null;
    String u = n.toUpperCase(Locale.ROOT).replace("ANSI ", "").replace("CP", "").strip();
    try {
      if (u.matches("\\d{3,5}")) {
        return Charset.forName(
            u.equals("65001") ? "UTF-8" : u.equals("28591") ? "ISO-8859-1" : "windows-" + u);
      }
      return Charset.forName(n);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  static String base(String p) {
    return VsiPath.baseName(p);
  }

  // ------------------------------------------------------------------ schema

  int recordCount() {
    return recordCount;
  }

  List<Column> columns() {
    return columns;
  }

  Charset charset() {
    return charset;
  }

  /** Field definitions as GDAL derives them from the descriptors. */
  List<FieldDefn> fieldDefns() {
    List<FieldDefn> out = new ArrayList<>();
    for (Column c : columns) {
      out.add(
          switch (c.type()) {
            case 'N', 'F' -> {
              if (c.decimals() > 0 || c.type() == 'F') {
                yield FieldDefn.of(c.name(), FieldType.REAL).withWidth(c.length(), c.decimals());
              }
              FieldType t =
                  c.length() < 10
                      ? FieldType.INTEGER
                      : c.length() < 19 ? FieldType.INTEGER64 : FieldType.REAL;
              yield FieldDefn.of(c.name(), t).withWidth(c.length(), 0);
            }
            case 'D' -> FieldDefn.of(c.name(), FieldType.DATE).withWidth(c.length(), 0);
            case 'L' ->
                FieldDefn.of(c.name(), FieldType.INTEGER, FieldSubType.BOOLEAN).withWidth(1, 0);
            default -> FieldDefn.of(c.name(), FieldType.STRING).withWidth(c.length(), 0);
          });
    }
    return out;
  }

  // ------------------------------------------------------------------ records

  private long recordPos(int i) {
    return headerLength + (long) i * recordLength;
  }

  boolean isDeleted(int i) {
    ByteBuffer b = file.read(recordPos(i), 1);
    return b.remaining() < 1 || b.get(0) == '*';
  }

  /**
   * Values of record {@code i} converted to the canonical field value classes; null for empty
   * values.
   */
  List<Object> read(int i, List<FieldDefn> defs) {
    ByteBuffer rec = file.read(recordPos(i), recordLength);
    List<Object> out = new ArrayList<>(columns.size());
    for (int k = 0; k < columns.size(); k++) {
      Column c = columns.get(k);
      if (c.offset() + c.length() > rec.limit()) {
        out.add(null);
        continue;
      }
      byte[] raw = new byte[c.length()];
      rec.get(c.offset(), raw);
      out.add(decode(c, defs.get(k), raw, i));
    }
    return out;
  }

  private Object decode(Column c, FieldDefn def, byte[] raw, int record) {
    int end = raw.length;
    if (c.type() == 'C' || c.type() == 'M') {
      while (end > 0 && (raw[end - 1] == ' ' || raw[end - 1] == 0)) end--;
      if (end == 0) return null; // as GDAL, an empty (all blank) string field is NULL
      return new String(raw, 0, end, charset);
    }
    String s = new String(raw, StandardCharsets.ISO_8859_1).strip();
    if (s.isEmpty() || s.chars().allMatch(ch -> ch == '*')) return null;
    try {
      return switch (c.type()) {
        case 'D' -> {
          if (s.matches("0+")) yield null;
          yield LocalDate.of(
              Integer.parseInt(s.substring(0, 4)),
              Integer.parseInt(s.substring(4, 6)),
              Integer.parseInt(s.substring(6, 8)));
        }
        case 'L' ->
            switch (s.charAt(0)) {
              case 'T', 't', 'Y', 'y' -> 1;
              case 'F', 'f', 'N', 'n' -> 0;
              default -> null;
            };
        default -> FieldValues.coerce(def, s.replace(',', '.'));
      };
    } catch (RuntimeException e) {
      Diagnostics.warn("Invalid value '" + s + "' for field " + c.name() + " in record " + record);
      return null;
    }
  }

  // ------------------------------------------------------------------ writing

  /** Writes record {@code i} (appending if {@code i == recordCount}). */
  void write(int i, List<Object> values, List<FieldDefn> defs) {
    byte[] rec = new byte[recordLength];
    java.util.Arrays.fill(rec, (byte) ' ');
    for (int k = 0; k < columns.size(); k++) {
      Column c = columns.get(k);
      byte[] cell = encode(c, defs.get(k), values.get(k));
      System.arraycopy(cell, 0, rec, c.offset(), c.length());
    }
    file.write(recordPos(i), ByteBuffer.wrap(rec));
    if (i >= recordCount) recordCount = i + 1;
  }

  void markDeleted(int i) {
    file.write(recordPos(i), ByteBuffer.wrap(new byte[] {'*'}));
  }

  private byte[] encode(Column c, FieldDefn def, Object v) {
    byte[] cell = new byte[c.length()];
    java.util.Arrays.fill(cell, (byte) ' ');
    if (v == null) {
      if (c.type() == 'D') java.util.Arrays.fill(cell, (byte) '0');
      return cell;
    }
    switch (c.type()) {
      case 'C' -> {
        byte[] b = v.toString().getBytes(charset);
        if (b.length > c.length()) {
          Diagnostics.warn(
              "Value of field " + c.name() + " has been truncated to " + c.length() + " bytes");
          b = cutAtCharBoundary(v.toString(), c.length());
        }
        System.arraycopy(b, 0, cell, 0, b.length);
      }
      case 'D' -> {
        LocalDate d = (LocalDate) FieldValues.coerce(FieldDefn.of(c.name(), FieldType.DATE), v);
        byte[] b =
            String.format("%04d%02d%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth())
                .getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(b, 0, cell, 0, Math.min(8, c.length()));
      }
      case 'L' -> cell[0] = (byte) (((Number) v).intValue() != 0 ? 'T' : 'F');
      default -> {
        String t;
        if (v instanceof Double dv && c.decimals() > 0) {
          t = String.format(Locale.ROOT, "%" + c.length() + "." + c.decimals() + "f", dv);
        } else if (v instanceof Double dv) {
          t =
              Double.isFinite(dv)
                      && dv == Math.rint(dv)
                      && Math.abs(dv) < 1e15
                      && def.type() != FieldType.REAL
                  ? Long.toString((long) (double) dv)
                  : dev.jdal.geom.Wkt.number(dv);
        } else {
          t = v.toString();
        }
        if (t.length() > c.length()) {
          Diagnostics.warn(
              "Value "
                  + t
                  + " of field "
                  + c.name()
                  + " does not fit in "
                  + c.length()
                  + " characters; written as asterisks");
          java.util.Arrays.fill(cell, (byte) '*');
        } else {
          byte[] b = t.getBytes(StandardCharsets.ISO_8859_1);
          System.arraycopy(b, 0, cell, c.length() - b.length, b.length);
        }
      }
    }
    return cell;
  }

  private byte[] cutAtCharBoundary(String s, int maxBytes) {
    StringBuilder sb = new StringBuilder();
    int bytes = 0;
    for (int i = 0; i < s.length(); ) {
      int cp = s.codePointAt(i);
      byte[] b = new String(Character.toChars(cp)).getBytes(charset);
      if (bytes + b.length > maxBytes) break;
      bytes += b.length;
      sb.appendCodePoint(cp);
      i += Character.charCount(cp);
    }
    return sb.toString().getBytes(charset);
  }

  /** Appends a column by rewriting all records; returns the reopened table (this one is closed). */
  DbfFile addColumn(Column c) {
    List<Column> newCols = new ArrayList<>(columns);
    newCols.add(new Column(c.name(), c.type(), c.length(), c.decimals(), recordLength));
    int newRecordLength = recordLength + c.length();
    int newHeaderLength = 32 + 32 * newCols.size() + 1;
    String tmp = path + ".tmp";
    try (BlockFile out = BlockFile.create(tmp)) {
      // header and descriptors are written by a temporary table object sharing our state
      DbfFile t = new DbfFile(tmp, out);
      t.columns.addAll(newCols);
      t.recordLength = newRecordLength;
      t.headerLength = newHeaderLength;
      t.recordCount = recordCount;
      t.ldid = ldid;
      t.charset = charset;
      t.writeHeader();
      byte[] blank = new byte[c.length()];
      java.util.Arrays.fill(blank, (byte) ' ');
      for (int i = 0; i < recordCount; i++) {
        ByteBuffer old = file.read(recordPos(i), recordLength);
        byte[] rec = new byte[newRecordLength];
        old.get(0, rec, 0, Math.min(recordLength, old.limit()));
        System.arraycopy(blank, 0, rec, recordLength, blank.length);
        out.write((long) newHeaderLength + (long) i * newRecordLength, ByteBuffer.wrap(rec));
      }
      out.write(
          (long) newHeaderLength + (long) recordCount * newRecordLength,
          ByteBuffer.wrap(new byte[] {0x1A}));
    }
    try {
      file.close();
      Vsi.global().rename(tmp, path);
    } catch (java.io.IOException e) {
      throw new JdalException("Cannot rewrite " + path + ": " + e.getMessage(), e);
    }
    return open(path, true);
  }

  void writeHeader() {
    ByteBuffer h = ByteBuffer.allocate(headerLength).order(ByteOrder.LITTLE_ENDIAN);
    LocalDate now = LocalDate.now();
    h.put(0, (byte) 0x03);
    h.put(1, (byte) (now.getYear() - 1900));
    h.put(2, (byte) now.getMonthValue());
    h.put(3, (byte) now.getDayOfMonth());
    h.putInt(4, recordCount);
    h.putShort(8, (short) headerLength);
    h.putShort(10, (short) recordLength);
    h.put(29, (byte) ldid);
    for (int i = 0; i < columns.size(); i++) {
      Column c = columns.get(i);
      int base = 32 + 32 * i;
      byte[] name = c.name().getBytes(StandardCharsets.ISO_8859_1);
      for (int k = 0; k < Math.min(10, name.length); k++) h.put(base + k, name[k]);
      h.put(base + 11, (byte) c.type());
      h.put(base + 16, (byte) c.length());
      h.put(base + 17, (byte) c.decimals());
    }
    h.put(headerLength - 1, (byte) 0x0D);
    file.write(0, h);
  }

  /** Writes the end-of-file marker and the header with the final record count. */
  void flush() {
    writeHeader();
    file.write(recordPos(recordCount), ByteBuffer.wrap(new byte[] {0x1A}));
    file.force();
  }

  @Override
  public void close() {
    file.close();
  }
}
