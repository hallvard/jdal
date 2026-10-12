package dev.jdal.driver.csv;

import dev.jdal.core.JdalException;
import dev.jdal.feature.Feature;
import dev.jdal.feature.FieldDefn;
import dev.jdal.geom.Geometry;
import dev.jdal.geom.Point;
import dev.jdal.geom.Wkt;
import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiFile;
import dev.jdal.vsi.VsiMode;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Appends records to an existing CSV file, following its column layout. */
final class CsvAppender implements AutoCloseable {

  private static final Vsi VSI = Vsi.global();

  private final CsvSchema schema;
  private final CsvFormat format;
  private final Writer out;

  private CsvAppender(String file, CsvSchema schema, Writer out) {
    this.schema = schema;
    this.format =
        new CsvFormat(
            schema.separator,
            "\n",
            CsvFormat.Quoting.IF_AMBIGUOUS,
            false,
            false,
            CsvFormat.Geom.NONE,
            true);
    this.out = out;
  }

  static CsvAppender forExisting(String file, CsvSchema schema) {
    try {
      boolean needsNewline = false;
      try (VsiFile f = VSI.open(file, VsiMode.READ)) {
        long length = f.size();
        if (length > 0) {
          ByteBuffer last = ByteBuffer.allocate(1);
          f.read(last, length - 1);
          needsNewline = last.get(0) != '\n' && last.get(0) != '\r';
        }
      }
      Writer w = VSI.newWriter(file, VsiMode.APPEND, StandardCharsets.UTF_8);
      if (needsNewline) w.write("\n");
      return new CsvAppender(file, schema, w);
    } catch (IOException e) {
      throw new JdalException("Cannot append to " + file + ": " + e.getMessage(), e);
    }
  }

  void write(Feature f) {
    List<String> cells = new ArrayList<>();
    List<FieldDefn> fields = schema.type.fields();
    for (int i = 0; i < schema.columnCount(); i++) {
      int fi = schema.fieldIndex.get(i);
      Geometry g = f.geometry();
      String text = null;
      boolean isString = false, forceQuote = false;
      if (i == schema.geomColumn && g != null) {
        text = Wkt.write(g);
        forceQuote = true;
      } else if (i == schema.xColumn && g instanceof Point p && !p.isEmpty())
        text = Wkt.number(p.x());
      else if (i == schema.yColumn && g instanceof Point p && !p.isEmpty())
        text = Wkt.number(p.y());
      else if (i == schema.zColumn && g instanceof Point p && p.dimension().hasZ())
        text = Wkt.number(p.z());
      else if (fi >= 0) {
        FieldDefn d = fields.get(fi);
        text = CsvFormat.valueText(d, f.get(fi));
        isString = d.type() == dev.jdal.feature.FieldType.STRING;
      }
      cells.add(format.cell(text, isString, forceQuote));
    }
    try {
      out.write(format.row(cells));
    } catch (IOException e) {
      throw new JdalException("Cannot append: " + e.getMessage(), e);
    }
  }

  void flush() {
    try {
      out.flush();
    } catch (IOException e) {
      throw new JdalException("Cannot append: " + e.getMessage(), e);
    }
  }

  @Override
  public void close() {
    try {
      out.close();
    } catch (IOException e) {
      throw new JdalException("Cannot close: " + e.getMessage(), e);
    }
  }
}
