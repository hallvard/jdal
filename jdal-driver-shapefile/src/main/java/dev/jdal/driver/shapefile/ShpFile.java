package dev.jdal.driver.shapefile;

import dev.jdal.core.JdalException;
import dev.jdal.geom.Envelope;
import dev.jdal.geom.Geometry;
import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiPath;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** The .shp and .shx files of a shapefile. */
final class ShpFile implements AutoCloseable {

  private static final int HEADER = 100;

  private final String path;
  private final BlockFile shp;
  private final BlockFile shx;
  private int shapeType;
  private int recordCount;
  private final ShpGeometry.Bounds bounds = new ShpGeometry.Bounds();
  private boolean headerDirty;

  private ShpFile(String path, BlockFile shp, BlockFile shx) {
    this.path = path;
    this.shp = shp;
    this.shx = shx;
  }

  static ShpFile open(String shpPath, boolean write) {
    Vsi vsi = Vsi.global();
    String shxPath =
        VsiPath.sibling(
            shpPath, DbfFile.base(shpPath) + (shpPath.endsWith(".SHP") ? ".SHX" : ".shx"));
    if (!vsi.exists(shxPath)) shxPath = VsiPath.sibling(shpPath, DbfFile.base(shpPath) + ".SHX");
    if (!vsi.exists(shxPath)) throw new JdalException("Missing .shx file for " + shpPath);
    ShpFile f =
        new ShpFile(shpPath, BlockFile.open(shpPath, write), BlockFile.open(shxPath, write));
    f.readHeader();
    return f;
  }

  static ShpFile create(String shpPath, int shapeType) {
    String shxPath = VsiPath.sibling(shpPath, DbfFile.base(shpPath) + ".shx");
    ShpFile f = new ShpFile(shpPath, BlockFile.create(shpPath), BlockFile.create(shxPath));
    f.shapeType = shapeType;
    f.headerDirty = true;
    f.writeHeaders();
    return f;
  }

  private void readHeader() {
    ByteBuffer h = shp.read(0, HEADER);
    if (h.limit() < HEADER || h.order(ByteOrder.BIG_ENDIAN).getInt(0) != 9994)
      throw new JdalException("Not a shapefile: " + path);
    h.order(ByteOrder.LITTLE_ENDIAN);
    shapeType = h.getInt(32);
    if (!ShpGeometry.isValid(shapeType))
      throw new JdalException("Unsupported shapefile type " + shapeType + " in " + path);
    bounds.xmin = h.getDouble(36);
    bounds.ymin = h.getDouble(44);
    bounds.xmax = h.getDouble(52);
    bounds.ymax = h.getDouble(60);
    bounds.zmin = h.getDouble(68);
    bounds.zmax = h.getDouble(76);
    bounds.mmin = h.getDouble(84);
    bounds.mmax = h.getDouble(92);
    long shxSize = shx.size();
    recordCount = (int) Math.max(0, (shxSize - HEADER) / 8);
  }

  int shapeType() {
    return shapeType;
  }

  int recordCount() {
    return recordCount;
  }

  /** Header extent or null for an empty file. */
  Envelope extent() {
    if (recordCount == 0 || !(bounds.xmin <= bounds.xmax) || Double.isNaN(bounds.xmin)) return null;
    return new Envelope(bounds.xmin, bounds.ymin, bounds.xmax, bounds.ymax);
  }

  ShpGeometry.Bounds bounds() {
    return bounds;
  }

  /**
   * Changes the type of an empty (or all null shape) file, e.g. when the type of a new layer was
   * not declared.
   */
  void setShapeType(int t) {
    shapeType = t;
    headerDirty = true;
  }

  private ByteBuffer entry(int i) {
    if (i < 0 || i >= recordCount) throw new JdalException("No record " + i + " in " + path);
    return shx.read(HEADER + 8L * i, 8).order(ByteOrder.BIG_ENDIAN);
  }

  /** Content of record {@code i}, little endian; empty buffer for records outside the file. */
  ByteBuffer content(int i) {
    ByteBuffer e = entry(i);
    long off = (e.getInt(0) & 0xFFFFFFFFL) * 2;
    int len = e.getInt(4) * 2;
    if (len < 0) throw new JdalException("Corrupt .shx entry for record " + i);
    ByteBuffer c = shp.read(off + 8, len);
    if (c.limit() < len)
      throw new JdalException("Truncated .shp: record " + i + " is beyond the end of the file");
    return c;
  }

  Geometry read(int i, boolean promoteToMulti) {
    return ShpGeometry.read(content(i), promoteToMulti, i);
  }

  Envelope envelope(int i) {
    return ShpGeometry.envelope(content(i));
  }

  /** Appends a record; returns its index. */
  int append(Geometry g) {
    byte[] content = ShpGeometry.encode(g, shapeType, bounds);
    long off = Math.max(shp.size(), HEADER);
    writeRecord(off, recordCount + 1, content);
    writeEntry(recordCount, off, content.length);
    headerDirty = true;
    return recordCount++;
  }

  /** Replaces record {@code i}: in place if it fits, else at the end of the file. */
  void replace(int i, Geometry g) {
    byte[] content = ShpGeometry.encode(g, shapeType, bounds);
    ByteBuffer e = entry(i);
    long off = (e.getInt(0) & 0xFFFFFFFFL) * 2;
    int oldLen = e.getInt(4) * 2;
    if (content.length > oldLen) off = Math.max(shp.size(), HEADER);
    writeRecord(off, i + 1, content);
    writeEntry(i, off, content.length);
    headerDirty = true;
  }

  private void writeRecord(long off, int number, byte[] content) {
    ByteBuffer b = ByteBuffer.allocate(8 + content.length).order(ByteOrder.BIG_ENDIAN);
    b.putInt(number).putInt(content.length / 2).put(content).flip();
    shp.write(off, b);
  }

  private void writeEntry(int i, long off, int len) {
    ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
    b.putInt((int) (off / 2)).putInt(len / 2).flip();
    shx.write(HEADER + 8L * i, b);
  }

  private ByteBuffer header(long fileLengthBytes) {
    ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.BIG_ENDIAN);
    h.putInt(0, 9994);
    h.putInt(24, (int) (fileLengthBytes / 2));
    h.order(ByteOrder.LITTLE_ENDIAN);
    h.putInt(28, 1000);
    h.putInt(32, shapeType);
    boolean empty = bounds.isEmpty();
    h.putDouble(36, empty ? 0 : bounds.xmin).putDouble(44, empty ? 0 : bounds.ymin);
    h.putDouble(52, empty ? 0 : bounds.xmax).putDouble(60, empty ? 0 : bounds.ymax);
    boolean z = bounds.zmin <= bounds.zmax, m = bounds.mmin <= bounds.mmax;
    h.putDouble(68, z ? bounds.zmin : 0).putDouble(76, z ? bounds.zmax : 0);
    h.putDouble(84, m ? bounds.mmin : 0).putDouble(92, m ? bounds.mmax : 0);
    return h;
  }

  void writeHeaders() {
    shp.write(0, header(Math.max(shp.size(), HEADER)));
    shx.write(0, header(HEADER + 8L * recordCount));
    headerDirty = false;
  }

  void flush() {
    if (headerDirty) writeHeaders();
    shp.force();
    shx.force();
  }

  @Override
  public void close() {
    try {
      if (headerDirty) writeHeaders();
    } finally {
      shp.close();
      shx.close();
    }
  }
}
