package dev.jdal.driver.csv;

import dev.jdal.core.*;
import dev.jdal.feature.*;
import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiPath;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** A CSV file being created: the header is written with the first feature (or on close). */
final class CsvWriteLayer implements Layer {

  private static final Vsi VSI = Vsi.global();

  private final String file;
  private final FeatureType type;
  private final CsvFormat format;
  private final List<CsvFormat.Column> columns;
  private Writer out;
  private boolean headerWritten;
  private long count;

  CsvWriteLayer(String file, FeatureType requested, Options options) {
    this.file = file;
    this.type = requested;
    this.format = CsvFormat.from(options);
    this.columns = format.columns(requested.fields());
    if (format.geom() == CsvFormat.Geom.NONE && !requested.geomFields().isEmpty()) {
      Diagnostics.warn(
          "Geometry of layer "
              + requested.name()
              + " is not written; use the GEOMETRY layer creation option");
    }
    try {
      VSI.mkdirs(VsiPath.parent(file));
      out = VSI.newWriter(file);
      if (format.bom()) out.write('﻿');
    } catch (IOException e) {
      throw new JdalException("Cannot create " + file + ": " + e.getMessage(), e);
    }
  }

  @Override
  public String name() {
    return type.name();
  }

  @Override
  public FeatureType type() {
    return type;
  }

  @Override
  public Set<Capability> capabilities() {
    return EnumSet.of(Capability.SEQUENTIAL_WRITE);
  }

  @Override
  public Stream<Feature> features() {
    return Stream.empty();
  }

  @Override
  public long count() {
    return count;
  }

  private void writeHeader() throws IOException {
    if (headerWritten) return;
    headerWritten = true;
    if (!format.header()) return;
    List<String> cells = new ArrayList<>();
    for (CsvFormat.Column c : columns) cells.add(format.cell(c.name(), true, false));
    out.write(format.row(cells));
  }

  @Override
  public synchronized Feature insert(Feature f) {
    if (f.values().size() != type.fields().size())
      throw new JdalException("Feature does not fit layer " + name());
    try {
      writeHeader();
      List<String> cells = new ArrayList<>();
      for (CsvFormat.Column c : columns) {
        boolean wkt = c.csvtType().equals("WKT");
        cells.add(format.cell(c.text().apply(f), c.isString() && !wkt, wkt));
      }
      out.write(format.row(cells));
      count++;
      return f.hasFid() ? f : f.withFid(count);
    } catch (IOException e) {
      throw new JdalException("Cannot write " + file + ": " + e.getMessage(), e);
    }
  }

  @Override
  public synchronized void sync() {
    try {
      writeHeader();
      out.flush();
    } catch (IOException e) {
      throw new JdalException("Cannot write " + file + ": " + e.getMessage(), e);
    }
  }

  @Override
  public synchronized void close() {
    if (out == null) return;
    try {
      writeHeader();
      out.close();
      out = null;
      if (format.csvt()) writeCsvt();
    } catch (IOException e) {
      throw new JdalException("Cannot write " + file + ": " + e.getMessage(), e);
    }
  }

  private void writeCsvt() throws IOException {
    List<String> types = new ArrayList<>();
    for (CsvFormat.Column c : columns) types.add('"' + c.csvtType() + '"');
    VSI.writeString(VsiPath.withExtension(file, "csvt"), String.join(",", types) + "\n");
  }
}
