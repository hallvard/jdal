package dev.jdal.driver.shapefile;

import dev.jdal.core.*;
import dev.jdal.feature.*;
import dev.jdal.geom.*;
import dev.jdal.srs.SpatialReference;
import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiPath;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** One shapefile: geometries in .shp/.shx, attributes in .dbf, spatial reference in .prj. */
final class ShapefileLayer implements Layer {

  private final String base; // path without extension
  private final String name;
  private ShpFile shp; // null for attribute-only tables
  private DbfFile dbf; // null for geometry-only files
  private final boolean writable;
  private final boolean promoteToMulti;
  private final SpatialReference srs;
  private FeatureType type;
  private List<FieldDefn> fields;
  private int liveCount = -1;

  private ShapefileLayer(
      String base,
      ShpFile shp,
      DbfFile dbf,
      boolean writable,
      boolean promote,
      SpatialReference srs) {
    this.base = base;
    this.name = VsiPath.fileName(base);
    this.shp = shp;
    this.dbf = dbf;
    this.writable = writable;
    this.promoteToMulti = promote;
    this.srs = srs;
  }

  // ------------------------------------------------------------------ open

  static ShapefileLayer open(String file, boolean update, Options o) {
    String n = VsiPath.fileName(file);
    String ext = n.substring(n.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    String base = VsiPath.sibling(file, DbfFile.base(file));
    ShpFile shp = null;
    DbfFile dbf = null;
    if (ext.equals("shp")) {
      shp = ShpFile.open(file, update);
      String d = sibling(file, "dbf");
      if (d != null) dbf = DbfFile.open(d, update);
    } else {
      dbf = DbfFile.open(file, update);
    }
    SpatialReference srs = null;
    String prj = sibling(file, "prj");
    if (prj != null) {
      try {
        srs = SpatialReference.fromWkt(Vsi.global().readString(prj));
      } catch (IOException | IllegalArgumentException e) {
        Diagnostics.warn("Cannot read " + prj + ": " + e.getMessage());
      }
    }
    ShapefileLayer l =
        new ShapefileLayer(
            base,
            shp,
            dbf,
            update,
            o.bool(
                "PROMOTE_TO_MULTI",
                !"NO"
                    .equalsIgnoreCase(
                        dev.jdal.config.Config.global().get("jdal.shape.promoteToMulti", "YES"))),
            srs);
    l.fields = dbf == null ? List.of() : dbf.fieldDefns();
    l.type = l.buildType();
    return l;
  }

  /** The sibling file with extension {@code ext} in either case, or null. */
  private static String sibling(String file, String ext) {
    String b = DbfFile.base(file);
    for (String e : List.of(ext, ext.toUpperCase(Locale.ROOT))) {
      String p = VsiPath.sibling(file, b + "." + e);
      if (Vsi.global().isFile(p)) return p;
    }
    return null;
  }

  private FeatureType buildType() {
    if (shp == null) return new FeatureType(name, fields, List.of());
    int st = shp.shapeType();
    GeometryType gt;
    Dimension d = Dimension.XY;
    if (ShpGeometry.isPoint(st)) gt = GeometryType.POINT;
    else if (ShpGeometry.isMultiPoint(st)) gt = GeometryType.MULTIPOINT;
    else if (ShpGeometry.isArc(st))
      gt = promoteToMulti ? GeometryType.MULTILINESTRING : GeometryType.LINESTRING;
    else if (ShpGeometry.isPolygon(st))
      gt = promoteToMulti ? GeometryType.MULTIPOLYGON : GeometryType.POLYGON;
    else gt = GeometryType.UNKNOWN;
    if (ShpGeometry.hasZ(st)) d = Dimension.XYZM;
    else if (ShpGeometry.isMOnly(st)) d = Dimension.XYM;
    if (d.hasM() && shp.recordCount() > 0 && !firstShapeUsesM()) d = Dimension.of(d.hasZ(), false);
    return new FeatureType(name, fields, List.of(new GeomFieldDefn("", gt, d, srs, true)));
  }

  private boolean firstShapeUsesM() {
    try {
      Geometry g = shp.read(0, true);
      if (g == null) return false;
      boolean[] used = {false};
      g.transform(
          c -> {
            if (c.length > 2 && !Double.isNaN(c[c.length - 1])) used[0] = true;
            return c;
          });
      return g.dimension().hasM() && used[0];
    } catch (JdalException e) {
      return false;
    }
  }

  // ------------------------------------------------------------------ create

  static ShapefileLayer create(
      String base, FeatureType requested, Options o, SpatialReference srsOverride) {
    String layerName = VsiPath.fileName(base);
    GeomFieldDefn g = requested.geomFields().isEmpty() ? null : requested.geomFields().getFirst();
    int shapeType = ShpGeometry.NULL;
    if (g != null) {
      shapeType = ShpGeometry.typeFor(g.type(), g.dimension());
      if (o.has("SHPT")) shapeType = shapeTypeOption(o.get("SHPT"), shapeType);
    }
    // attribute table
    String enc = o.get("ENCODING", "LDID/87");
    Charset cs;
    int ldid = 0;
    boolean writeCpg = false;
    if (enc.regionMatches(true, 0, "LDID/", 0, 5)) {
      ldid = Integer.parseInt(enc.substring(5).strip());
      cs = ldid == 87 || ldid == 3 ? StandardCharsets.ISO_8859_1 : charsetForLdid(ldid);
    } else {
      cs = DbfFile.charsetNamed(enc);
      if (cs == null) throw new JdalException("Unknown ENCODING " + enc);
      writeCpg = true;
    }
    List<DbfFile.Column> cols = new ArrayList<>();
    Set<String> used = new HashSet<>();
    for (FieldDefn f : requested.fields()) cols.add(column(f, used));
    String dbfPath = VsiPath.sibling(base, layerName + ".dbf");
    DbfFile dbf = DbfFile.create(dbfPath, cols, cs, ldid);
    ShpFile shp = null;
    if (g != null) shp = ShpFile.create(VsiPath.sibling(base, layerName + ".shp"), shapeType);
    SpatialReference srs = srsOverride != null ? srsOverride : g == null ? null : g.srs();
    try {
      if (writeCpg)
        Vsi.global()
            .writeString(
                VsiPath.sibling(base, layerName + ".cpg"),
                enc.toUpperCase(Locale.ROOT).replace("UTF8", "UTF-8"));
      if (srs != null && g != null) {
        Optional<String> wkt = srs.toEsriWkt();
        if (wkt.isPresent())
          Vsi.global().writeString(VsiPath.sibling(base, layerName + ".prj"), wkt.get());
        else Diagnostics.warn("Cannot write a .prj file for " + srs.definition());
      }
    } catch (IOException e) {
      throw new JdalException("Cannot write " + base + ": " + e.getMessage(), e);
    }
    ShapefileLayer l =
        new ShapefileLayer(base, shp, dbf, true, o.bool("PROMOTE_TO_MULTI", true), srs);
    l.fields = dbf.fieldDefns();
    List<GeomFieldDefn> geoms =
        g == null ? List.of() : List.of(new GeomFieldDefn("", g.type(), g.dimension(), srs, true));
    l.type = new FeatureType(layerName, l.fields, geoms);
    return l;
  }

  private static int shapeTypeOption(String v, int fallback) {
    return switch (v.toUpperCase(Locale.ROOT)) {
      case "POINT" -> ShpGeometry.POINT;
      case "ARC" -> ShpGeometry.ARC;
      case "POLYGON" -> ShpGeometry.POLYGON;
      case "MULTIPOINT" -> ShpGeometry.MULTIPOINT;
      case "POINTZ" -> ShpGeometry.POINTZ;
      case "ARCZ" -> ShpGeometry.ARCZ;
      case "POLYGONZ" -> ShpGeometry.POLYGONZ;
      case "MULTIPOINTZ" -> ShpGeometry.MULTIPOINTZ;
      case "POINTM" -> ShpGeometry.POINTM;
      case "ARCM" -> ShpGeometry.ARCM;
      case "POLYGONM" -> ShpGeometry.POLYGONM;
      case "MULTIPOINTM" -> ShpGeometry.MULTIPOINTM;
      case "NULL" -> ShpGeometry.NULL;
      default -> throw new JdalException("Unsupported SHPT value " + v);
    };
  }

  private static Charset charsetForLdid(int ldid) {
    return switch (ldid) {
      case 1 -> Charset.forName("IBM437");
      case 2, 10 -> Charset.forName("IBM850");
      default -> StandardCharsets.ISO_8859_1;
    };
  }

  /**
   * DBF column for a field: names are cut to 10 bytes and made unique, types mapped as GDAL does.
   */
  private static DbfFile.Column column(FieldDefn f, Set<String> used) {
    String n = f.name();
    if (n.getBytes(StandardCharsets.ISO_8859_1).length > 10) {
      String cut = n.substring(0, 10);
      Diagnostics.warn("Normalized/laundered field name: '" + n + "' to '" + cut + "'");
      n = cut;
    }
    String candidate = n;
    for (int i = 1; !used.add(candidate.toUpperCase(Locale.ROOT)); i++) {
      String suffix = "_" + i;
      candidate = n.substring(0, Math.min(n.length(), 10 - suffix.length())) + suffix;
    }
    int w = f.width(), p = f.precision();
    return switch (f.type()) {
      case INTEGER ->
          f.subType() == FieldSubType.BOOLEAN
              ? new DbfFile.Column(candidate, 'L', 1, 0, 0)
              : new DbfFile.Column(
                  candidate, 'N', w > 0 ? w : f.subType() == FieldSubType.INT16 ? 5 : 9, 0, 0);
      case INTEGER64 -> new DbfFile.Column(candidate, 'N', w > 0 ? w : 18, 0, 0);
      case REAL -> new DbfFile.Column(candidate, 'N', w > 0 ? w : 24, w > 0 ? p : 15, 0);
      case STRING -> new DbfFile.Column(candidate, 'C', w > 0 ? Math.min(w, 254) : 80, 0, 0);
      case DATE -> new DbfFile.Column(candidate, 'D', 8, 0, 0);
      case DATETIME -> {
        Diagnostics.warn(
            "Field " + f.name() + " created as date field, though DateTime requested.");
        yield new DbfFile.Column(candidate, 'D', 8, 0, 0);
      }
      case TIME -> new DbfFile.Column(candidate, 'C', 12, 0, 0);
      case BINARY -> new DbfFile.Column(candidate, 'C', 254, 0, 0);
      case INTEGER_LIST, INTEGER64_LIST, REAL_LIST, STRING_LIST ->
          new DbfFile.Column(candidate, 'C', 254, 0, 0);
    };
  }

  // ------------------------------------------------------------------ reading

  @Override
  public String name() {
    return name;
  }

  @Override
  public FeatureType type() {
    return type;
  }

  @Override
  public Set<Capability> capabilities() {
    EnumSet<Capability> c =
        EnumSet.of(
            Capability.RANDOM_READ,
            Capability.FAST_FEATURE_COUNT,
            Capability.Z_GEOMETRIES,
            Capability.MEASURED_GEOMETRIES);
    if (shp != null) c.add(Capability.FAST_EXTENT);
    if (writable)
      c.addAll(
          EnumSet.of(
              Capability.SEQUENTIAL_WRITE,
              Capability.RANDOM_WRITE,
              Capability.DELETE_FEATURE,
              Capability.CREATE_FIELD));
    return c;
  }

  private int totalRecords() {
    return shp != null ? shp.recordCount() : dbf != null ? dbf.recordCount() : 0;
  }

  private boolean isDeleted(int i) {
    return dbf != null && i < dbf.recordCount() && dbf.isDeleted(i);
  }

  private Feature build(int i) {
    List<Object> values =
        dbf == null
            ? List.of()
            : i < dbf.recordCount()
                ? dbf.read(i, fields)
                : new ArrayList<>(Collections.nCopies(fields.size(), null));
    List<Geometry> geoms = List.of();
    if (shp != null) geoms = Collections.singletonList(shp.read(i, promoteToMulti));
    return new Feature(type, i, values, geoms);
  }

  @Override
  public Stream<Feature> features() {
    return features(Query.ALL);
  }

  @Override
  public Stream<Feature> features(Query q) {
    java.util.function.Predicate<Feature> sql =
        q.where() == null ? null : SqlEngine.require().compileFilter(type, q.where());
    Iterator<Feature> it =
        new Iterator<>() {
          int next = 0;
          long returned = 0;
          Feature pending;

          private void advance() {
            int n = totalRecords();
            while (pending == null && next < n && (q.limit() < 0 || returned < q.limit())) {
              int i = next++;
              if (isDeleted(i)) continue;
              Envelope box = q.candidateBox();
              if (box != null) {
                if (shp == null) continue;
                Envelope e = shp.envelope(i);
                if (e == null || !e.intersects(box)) continue;
              }
              Feature f = build(i);
              if (q.spatial() != null && !q.spatial().test(f.geometry())) continue;
              if (q.filter() != null && !q.filter().test(f)) continue;
              if (sql != null && !sql.test(f)) continue;
              pending = f;
              returned++;
            }
          }

          @Override
          public boolean hasNext() {
            advance();
            return pending != null;
          }

          @Override
          public Feature next() {
            advance();
            if (pending == null) throw new NoSuchElementException();
            Feature f = pending;
            pending = null;
            return f;
          }
        };
    return StreamSupport.stream(
        Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL), false);
  }

  @Override
  public Optional<Feature> feature(long fid) {
    if (fid < 0 || fid >= totalRecords() || isDeleted((int) fid)) return Optional.empty();
    return Optional.of(build((int) fid));
  }

  @Override
  public long count() {
    if (liveCount < 0) {
      int n = totalRecords(), c = 0;
      for (int i = 0; i < n; i++) if (!isDeleted(i)) c++;
      liveCount = c;
    }
    return liveCount;
  }

  @Override
  public Optional<Envelope> extent() {
    return shp == null ? Optional.empty() : Optional.ofNullable(shp.extent());
  }

  // ------------------------------------------------------------------ writing

  private void checkWritable() {
    if (!writable) throw new JdalException("Shapefile opened read-only");
  }

  private List<Object> cells(Feature f) {
    List<Object> out = new ArrayList<>(f.values().size());
    for (int k = 0; k < f.values().size(); k++) {
      Object v = f.get(k);
      FieldDefn d = fields.get(k);
      out.add(
          switch (v) {
            case null -> null;
            case List<?> l -> dev.jdal.json.Json.write(dev.jdal.json.Json.of(l));
            case byte[] b -> HexFormat.of().formatHex(b);
            case java.time.LocalDateTime t -> t.toLocalDate();
            case java.time.OffsetDateTime t -> t.toLocalDate();
            case java.time.LocalTime t -> FieldValues.format(d, t);
            default -> v;
          });
    }
    return out;
  }

  private void checkShape(Feature f) {
    if (f.values().size() != fields.size())
      throw new JdalException("Feature does not fit layer " + name);
    if (f.geometry() != null && shp == null)
      throw new JdalException("Layer " + name + " has no geometry");
  }

  @Override
  public synchronized Feature insert(Feature f) {
    checkWritable();
    checkShape(f);
    Geometry g = f.geometry();
    int index;
    if (shp != null) {
      if (shp.shapeType() == ShpGeometry.NULL && g != null && !g.isEmpty()) {
        shp.setShapeType(ShpGeometry.typeFor(g));
        GeomFieldDefn old = type.geomFields().getFirst();
        type =
            type.withGeomFields(
                List.of(
                    old.withType(
                        old.type() == GeometryType.UNKNOWN ? g.type() : old.type(),
                        g.dimension())));
      }
      index = shp.append(g);
    } else {
      index = dbf.recordCount();
    }
    if (dbf != null) dbf.write(index, cells(f), fields);
    liveCount = -1;
    return new Feature(type, index, f.values(), f.geometries());
  }

  @Override
  public synchronized void update(Feature f) {
    checkWritable();
    checkShape(f);
    if (!f.hasFid() || f.fid() >= totalRecords() || isDeleted((int) f.fid())) {
      throw new JdalException("No feature with fid " + f.fid() + " in " + name);
    }
    int i = (int) f.fid();
    if (shp != null) shp.replace(i, f.geometry());
    if (dbf != null) dbf.write(i, cells(f), fields);
  }

  @Override
  public synchronized boolean delete(long fid) {
    checkWritable();
    if (fid < 0 || fid >= totalRecords() || isDeleted((int) fid)) return false;
    if (dbf == null) throw new JdalException("Cannot delete from a shapefile without a .dbf");
    dbf.markDeleted((int) fid);
    liveCount = -1;
    return true;
  }

  @Override
  public synchronized void addField(FieldDefn field) {
    checkWritable();
    if (dbf == null) throw new JdalException("Layer " + name + " has no attribute table");
    Set<String> used = new HashSet<>();
    for (DbfFile.Column c : dbf.columns()) used.add(c.name().toUpperCase(Locale.ROOT));
    dbf.flush();
    dbf = dbf.addColumn(column(field, used));
    fields = dbf.fieldDefns();
    type = type.withFields(fields);
  }

  @Override
  public synchronized void sync() {
    if (!writable) return;
    if (shp != null) shp.flush();
    if (dbf != null) dbf.flush();
  }

  @Override
  public synchronized void close() {
    if (writable) sync();
    if (shp != null) shp.close();
    if (dbf != null) dbf.close();
    shp = null;
    dbf = null;
  }
}
