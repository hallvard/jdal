package dev.jdal.sql;

import dev.jdal.core.Capability;
import dev.jdal.core.JdalException;
import dev.jdal.core.Layer;
import dev.jdal.feature.*;
import dev.jdal.geom.*;
import dev.jdal.sql.Bound.*;
import dev.jdal.srs.SpatialReference;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Stream;

/**
 * The result of a SELECT, a port of GDAL's {@code OGRGenSQLResultsLayer}. Read only; evaluates
 * lazily where it can.
 */
final class SelectLayer implements Layer {

  private static final String NULL_MARKER = "__OGR_NULL__";

  /** An output column and where its value goes in the result feature. */
  private record Out(Plan.Column col, boolean geometry, int index, FieldDefn field) {}

  private final Plan plan;
  private final dev.jdal.core.SpatialFilter spatialFilter;
  private final List<Out> outs = new ArrayList<>();
  private FeatureType type;
  private boolean summaryDone;
  private List<Feature> cached; // summary and distinct results

  SelectLayer(Plan plan, Geometry spatialFilter) {
    this.plan = plan;
    this.spatialFilter =
        spatialFilter == null || spatialFilter.isEmpty()
            ? null
            : new dev.jdal.core.SpatialFilter(dev.jdal.core.SpatialOp.INTERSECTS, spatialFilter);
    buildSchema();
    if (this.spatialFilter != null
        && type.geomFields().isEmpty()
        && plan.tables.getFirst().type().geomFields().isEmpty()) {
      throw new JdalException("Cannot set spatial filter: no geometry field present in layer");
    }
  }

  // ------------------------------------------------------------------ schema

  private void buildSchema() {
    List<FieldDefn> fields = new ArrayList<>();
    List<GeomFieldDefn> geoms = new ArrayList<>();
    List<Plan.Column> cols = new ArrayList<>(plan.columns);
    FeatureType src = plan.tables.getFirst().type();
    boolean hasGeom = false;
    for (Plan.Column c : cols) {
      if (!c.hidden() && isGeometry(c)) hasGeom = true;
    }
    if (plan.mode == Plan.Mode.RECORDSET
        && !hasGeom
        && src.geomFields().size() == 1
        && !plan.excludedGeometry) {
      String gn = src.geomFields().getFirst().name();
      cols.add(
          new Plan.Column(
              "",
              gn.isEmpty() ? FieldList.DEFAULT_GEOMETRY_NAME : gn,
              null,
              false,
              false,
              null,
              null,
              0,
              new FieldRef(FieldRef.Kind.GEOM, 0),
              SqlType.GEOMETRY,
              null));
    }
    for (Plan.Column c : cols) {
      if (c.hidden()) continue;
      boolean geom = isGeometry(c);
      String fieldName = c.fieldName();
      if (fieldName.isEmpty() && !geom) fieldName = "FIELD_" + (fields.size() + 1);
      String name;
      if (c.alias() != null) name = c.alias();
      else if (c.aggregate() != null) name = c.aggregate().sqlName + "_" + fieldName;
      else name = (c.tableName().isEmpty() ? "" : c.tableName() + ".") + fieldName;
      if (geom) {
        GeometryType gt = GeometryType.UNKNOWN;
        Dimension dim = Dimension.XY;
        SpatialReference srs = null;
        if (c.ref() != null && c.ref().kind() == FieldRef.Kind.GEOM) {
          GeomFieldDefn g = plan.tables.get(c.table()).type().geomFields().get(c.ref().index());
          gt = g.type();
          dim = g.dimension();
          srs = g.srs();
        }
        if (c.cast() != null && c.cast().geomType() != null) {
          gt = geometryTypeOf(c.cast().geomType());
          if (c.cast().srid() > 0) srs = SpatialReference.fromEpsg(c.cast().srid());
        }
        outs.add(new Out(c, true, geoms.size(), null));
        geoms.add(new GeomFieldDefn(name, gt, dim, srs, true));
      } else {
        FieldDefn d =
            plan.fieldDefFor(
                new Plan.Column(
                    c.tableName(),
                    fieldName,
                    c.alias(),
                    c.hidden(),
                    c.distinct(),
                    c.aggregate(),
                    c.expr(),
                    c.table(),
                    c.ref(),
                    c.fieldType(),
                    c.cast()),
                name);
        outs.add(new Out(c, false, fields.size(), d));
        fields.add(d);
      }
    }
    this.type = new FeatureType(plan.tables.getFirst().alias(), fields, geoms);
  }

  private static boolean isGeometry(Plan.Column c) {
    if (c.aggregate() == SqlOp.COUNT) return false;
    if (c.ref() != null && c.ref().kind() == FieldRef.Kind.GEOM) return true;
    return c.cast() != null && c.cast().type() == SqlType.GEOMETRY;
  }

  private static GeometryType geometryTypeOf(String name) {
    String n = name.toUpperCase(Locale.ROOT);
    if (n.endsWith("Z")) n = n.substring(0, n.length() - 1);
    try {
      return GeometryType.fromName(n);
    } catch (IllegalArgumentException e) {
      return GeometryType.UNKNOWN;
    }
  }

  @Override
  public String name() {
    return type().name();
  }

  @Override
  public FeatureType type() {
    if (plan.mode == Plan.Mode.SUMMARY) summary();
    return type;
  }

  @Override
  public Set<Capability> capabilities() {
    return EnumSet.of(Capability.FAST_FEATURE_COUNT);
  }

  // ------------------------------------------------------------------ reading

  private Stream<Feature> sourceFeatures() {
    Layer src = plan.tables.getFirst().layer();
    Stream<Feature> s = src.features();
    FeatureType st = plan.tables.getFirst().type();
    if (plan.where != null || spatialFilter != null) {
      s =
          s.filter(
              f -> {
                if (spatialFilter != null) {
                  Geometry g = f.geometries().isEmpty() ? null : f.geometry();
                  if (!spatialFilter.test(g)) return false;
                }
                if (plan.where == null) return true;
                return Eval.isTrue(Eval.eval(plan.where, c -> Values.fetch(f, st, c)));
              });
    }
    return s;
  }

  @Override
  public Stream<Feature> features() {
    Stream<Feature> s =
        switch (plan.mode) {
          case RECORDSET -> recordset();
          case SUMMARY -> {
            summary();
            yield cached.stream();
          }
          case DISTINCT -> {
            distinct();
            yield cached.stream();
          }
        };
    if (plan.offset > 0) s = s.skip(plan.offset);
    if (plan.limit >= 0) s = s.limit(plan.limit);
    return s;
  }

  private Stream<Feature> recordset() {
    Stream<Feature> src;
    if (plan.order.isEmpty()) {
      src = sourceFeatures();
    } else {
      List<Feature> list;
      try (Stream<Feature> all = sourceFeatures()) {
        list = new ArrayList<>(all.toList());
      }
      list.sort(this::compare);
      src = list.stream();
    }
    Joiner joiner = plan.joins.isEmpty() ? null : new Joiner(plan);
    return src.map(f -> translate(f, joiner));
  }

  @Override
  public long count() {
    if (plan.mode == Plan.Mode.RECORDSET
        && plan.where == null
        && spatialFilter == null
        && plan.limit < 0
        && plan.offset == 0) {
      return plan.tables.getFirst().layer().count();
    }
    try (Stream<Feature> s = features()) {
      return s.count();
    }
  }

  @Override
  public Optional<Feature> feature(long fid) {
    try (Stream<Feature> s = features()) {
      return s.filter(f -> f.fid() == fid).findFirst();
    }
  }

  @Override
  public void close() {
    for (var d : plan.extraDatasets) d.close();
  }

  // ------------------------------------------------------------------ record translation

  private Feature translate(Feature src, Joiner joiner) {
    Feature[] rows = new Feature[plan.tables.size()];
    rows[0] = src;
    if (joiner != null) joiner.fill(rows);
    Object[] values = new Object[type.fields().size()];
    Geometry[] geoms = new Geometry[type.geomFields().size()];
    for (Out o : outs) {
      Plan.Column c = o.col();
      if (c.isPlainField()) {
        Feature f = rows[c.table()];
        FieldRef ref = c.ref();
        switch (ref.kind()) {
          case GEOM -> geoms[o.index()] = f == null ? null : f.geometry(ref.index());
          case FIELD -> {
            if (f != null) {
              FieldDefn sd = plan.tables.get(c.table()).type().fields().get(ref.index());
              values[o.index()] = Values.convert(f.get(ref.index()), sd, o.field());
            }
          }
          default -> {
            Val v =
                Values.fetch(
                    f,
                    plan.tables.get(c.table()).type(),
                    new BColumn(c.table(), ref, c.fieldType(), c.fieldName()));
            values[o.index()] =
                Values.assign(
                    o.field(),
                    v.type().isInteger()
                        ? (Object) v.i()
                        : v.type() == SqlType.FLOAT ? (Object) v.f() : v.s());
          }
        }
      } else if (c.expr() != null) {
        Val v =
            Eval.eval(
                c.expr(),
                col -> Values.fetch(rows[col.table()], plan.tables.get(col.table()).type(), col));
        if (v.isNull()) continue;
        if (o.geometry()) {
          geoms[o.index()] =
              v.g() == null ? null : forceType(v.g(), type.geomFields().get(o.index()).type());
        } else {
          Object raw =
              v.type().isInteger() || v.type() == SqlType.BOOLEAN
                  ? (Object) v.i()
                  : v.type() == SqlType.FLOAT ? (Object) v.f() : v.s();
          values[o.index()] = Values.assign(o.field(), raw);
        }
      }
    }
    return new Feature(type, src.fid(), Arrays.asList(values), Arrays.asList(geoms));
  }

  /**
   * The conversions GDAL applies when a CAST demands a geometry type (polygon to multipolygon and
   * back, ...).
   */
  private static Geometry forceType(Geometry g, GeometryType wanted) {
    if (wanted == g.type() || wanted == GeometryType.UNKNOWN) return g;
    return switch (wanted) {
      case MULTIPOLYGON -> g instanceof Polygon p ? MultiPolygon.of(g.dimension(), List.of(p)) : g;
      case MULTILINESTRING ->
          g instanceof LineString l ? MultiLineString.of(g.dimension(), List.of(l)) : g;
      case POLYGON ->
          g instanceof MultiPolygon m && m.parts().size() == 1 ? m.parts().getFirst() : g;
      case LINESTRING ->
          g instanceof MultiLineString m && m.parts().size() == 1 ? m.parts().getFirst() : g;
      default -> g;
    };
  }

  // ------------------------------------------------------------------ ordering

  private int compare(Feature a, Feature b) {
    FeatureType st = plan.tables.getFirst().type();
    for (Plan.OrderKey k : plan.order) {
      Object x = sortValue(a, st, k), y = sortValue(b, st, k);
      int r;
      if (x == null) r = y == null ? 0 : -1;
      else if (y == null) r = 1;
      else r = compareValues(x, y, k.type());
      if (!k.ascending()) r = -r;
      if (r != 0) return r;
    }
    return 0;
  }

  private Object sortValue(Feature f, FeatureType st, Plan.OrderKey k) {
    if (k.ref().kind() == FieldRef.Kind.FIELD) return f.get(k.ref().index());
    Val v = Values.fetch(f, st, new BColumn(0, k.ref(), k.type(), k.name()));
    return v.type().isInteger()
        ? (Object) v.i()
        : v.type() == SqlType.FLOAT ? (Object) v.f() : v.s();
  }

  private static int compareValues(Object x, Object y, SqlType t) {
    if (x instanceof Number a && y instanceof Number b) {
      if (x instanceof Double || y instanceof Double)
        return Double.compare(a.doubleValue(), b.doubleValue());
      return Long.compare(a.longValue(), b.longValue());
    }
    if (x instanceof String a && y instanceof String b) return compareCodePoints(a, b);
    if (t.isDateLike() || x instanceof java.time.temporal.TemporalAccessor) {
      double[] da = Text.parseDate(FieldValues.format(FieldDefn.of("x", FieldType.DATETIME), x));
      double[] db = Text.parseDate(FieldValues.format(FieldDefn.of("x", FieldType.DATETIME), y));
      return da == null || db == null ? 0 : Text.compareDate(da, db);
    }
    return 0;
  }

  static int compareCodePoints(String a, String b) {
    int i = 0, j = 0;
    while (i < a.length() && j < b.length()) {
      int ca = a.codePointAt(i), cb = b.codePointAt(j);
      if (ca != cb) return Integer.compare(ca, cb);
      i += Character.charCount(ca);
      j += Character.charCount(cb);
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  // ------------------------------------------------------------------ summary (aggregates)

  /** Running totals of one aggregate column. */
  private static final class Summary {
    long count;
    double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
    String osMin = "9999/99/99 99:99:99", osMax = "0000/00/00 00:00:00";
    double sumAcc, sumCorrection;
    boolean sumOnlyFinite = true;
    double mean, sqDist;
    TreeSet<String> distinct;
    final List<String> distinctOrder = new ArrayList<>();

    double sum() {
      return sumOnlyFinite ? sumAcc + sumCorrection : sumAcc;
    }
  }

  private Comparator<String> distinctComparator(Plan.Column c, boolean asc) {
    Comparator<String> base =
        (a, b) -> {
          if (a.equals(NULL_MARKER)) return b.equals(NULL_MARKER) ? 0 : -1;
          if (b.equals(NULL_MARKER)) return 1;
          return switch (c.fieldType()) {
            case INTEGER -> Long.compare(CNum.atoll(a), CNum.atoll(b));
            case FLOAT -> Double.compare(CNum.atof(a), CNum.atof(b));
            default -> compareCodePoints(a, b);
          };
        };
    return asc ? base : base.reversed();
  }

  private synchronized void summary() {
    if (summaryDone) return;
    summaryDone = true;
    FeatureType st = plan.tables.getFirst().type();
    Summary[] sums = new Summary[outs.size()];
    for (int i = 0; i < sums.length; i++) {
      sums[i] = new Summary();
      Plan.Column c = outs.get(i).col();
      if (c.distinct()) {
        boolean asc = plan.order.isEmpty() || plan.order.getFirst().ascending();
        sums[i].distinct = new TreeSet<>(distinctComparator(c, asc));
      }
    }
    if (plan.order.size() > 1 && plan.mode == Plan.Mode.DISTINCT) {
      throw new JdalException("Can't ORDER BY a DISTINCT list by more than one key.");
    }
    if (plan.mode == Plan.Mode.DISTINCT
        && !plan.order.isEmpty()
        && !Objects.equals(plan.order.getFirst().ref(), outs.getFirst().col().ref())) {
      throw new JdalException("Only selected DISTINCT field can be used for ORDER BY.");
    }
    boolean any = false;
    try (Stream<Feature> src = sourceFeatures()) {
      for (Iterator<Feature> it = src.iterator(); it.hasNext(); ) {
        Feature f = it.next();
        any = true;
        for (int i = 0; i < sums.length; i++) accumulate(outs.get(i).col(), sums[i], f, st);
      }
    }
    List<Feature> result = new ArrayList<>();
    if (plan.mode == Plan.Mode.SUMMARY) {
      Object[] values = new Object[type.fields().size()];
      List<FieldDefn> defs = new ArrayList<>(type.fields());
      for (int i = 0; i < sums.length; i++) {
        Out o = outs.get(i);
        Plan.Column c = o.col();
        Summary s = sums[i];
        if (c.aggregate() == SqlOp.COUNT) {
          FieldDefn d = defs.get(o.index());
          if (s.count <= Integer.MAX_VALUE) {
            d = d.withType(FieldType.INTEGER, d.subType());
            defs.set(o.index(), d);
          }
          values[o.index()] = Values.assign(d, s.count);
          continue;
        }
        if (!any) continue;
        values[o.index()] = aggregateResult(c, s, o.field());
      }
      type = type.withFields(defs);
      result.add(new Feature(type, 0, Arrays.asList(values), List.of()));
    } else {
      Summary s = sums[0];
      Out o = outs.getFirst();
      List<String> values = plan.order.isEmpty() ? s.distinctOrder : new ArrayList<>(s.distinct);
      long fid = 0;
      for (String v : values) {
        Object[] cells = new Object[type.fields().size()];
        cells[o.index()] = v.equals(NULL_MARKER) ? null : Values.assign(o.field(), v);
        result.add(new Feature(type, fid++, Arrays.asList(cells), List.of()));
      }
    }
    cached = result;
  }

  private void distinct() {
    summary();
  }

  private void accumulate(Plan.Column c, Summary s, Feature f, FeatureType st) {
    if (c.aggregate() == SqlOp.COUNT && c.ref() == null) { // COUNT(*)
      s.count++;
      return;
    }
    Val v;
    if (c.ref().kind() == FieldRef.Kind.GEOM) {
      Geometry g = f.geometry(c.ref().index());
      if (c.aggregate() == SqlOp.COUNT) {
        if (g != null) s.count++;
      }
      return;
    }
    v = Values.fetch(f, st, new BColumn(0, c.ref(), c.fieldType(), c.fieldName()));
    if (c.aggregate() == SqlOp.COUNT) {
      if (!v.isNull()) {
        if (!c.distinct()) s.count++;
        else addDistinct(s, stringOf(v, f, st, c));
      }
      return;
    }
    if (v.isNull()) {
      if (c.distinct()) addDistinct(s, NULL_MARKER);
      return;
    }
    boolean numeric =
        c.fieldType() == SqlType.BOOLEAN
            || c.fieldType().isInteger()
            || c.fieldType() == SqlType.FLOAT;
    if (!c.distinct() && numeric) {
      numeric(c, s, v.asDouble());
    } else if (c.distinct()) {
      addDistinct(s, stringOf(v, f, st, c));
    } else {
      text(c, s, stringOf(v, f, st, c));
    }
  }

  private static String stringOf(Val v, Feature f, FeatureType st, Plan.Column c) {
    if (v.type().isInteger() || v.type() == SqlType.BOOLEAN) return Long.toString(v.i());
    if (v.type() == SqlType.FLOAT) return Wkt.number(v.f());
    return v.s() == null ? "" : v.s();
  }

  private static void addDistinct(Summary s, String value) {
    if (s.distinct.add(value)) {
      s.distinctOrder.add(value);
      s.count++;
    }
  }

  private static void numeric(Plan.Column c, Summary s, double d) {
    switch (c.aggregate()) {
      case MIN -> {
        if (d < s.min) s.min = d;
        s.count++;
      }
      case MAX -> {
        if (d > s.max) s.max = d;
        s.count++;
      }
      case AVG, SUM -> {
        s.count++;
        double newSum = s.sumAcc + d;
        if (s.sumOnlyFinite && Double.isFinite(d)) {
          if (Math.abs(s.sumAcc) >= Math.abs(d)) s.sumCorrection += (s.sumAcc - newSum) + d;
          else s.sumCorrection += (d - newSum) + s.sumAcc;
        } else {
          s.sumOnlyFinite = false;
        }
        s.sumAcc = newSum;
      }
      case STDDEV_POP, STDDEV_SAMP -> variance(s, d);
      default -> {}
    }
  }

  private static void variance(Summary s, double d) {
    s.count++;
    double delta = d - s.mean;
    s.mean += delta / s.count;
    s.sqDist += delta * (d - s.mean);
  }

  private static void text(Plan.Column c, Summary s, String t) {
    switch (c.aggregate()) {
      case MIN -> {
        if (!t.isEmpty()) {
          if (s.count == 0 || compareCodePoints(t, s.osMin) < 0) s.osMin = t;
          s.count++;
        }
      }
      case MAX -> {
        if (!t.isEmpty()) {
          if (s.count == 0 || compareCodePoints(t, s.osMax) > 0) s.osMax = t;
          s.count++;
        }
      }
      case AVG, SUM, STDDEV_POP, STDDEV_SAMP -> {
        if (!c.fieldType().isDateLike()) {
          throw new JdalException(
              "swq_select_summarize() - "
                  + c.aggregate().sqlName
                  + "() called on unexpected field type");
        }
        double[] d = Text.parseDate(t);
        if (d == null) return;
        double secs =
            LocalDateTime.of(
                        (int) d[0],
                        Math.max(1, (int) d[1]),
                        Math.max(1, (int) d[2]),
                        (int) d[3],
                        (int) d[4],
                        (int) d[5])
                    .toEpochSecond(ZoneOffset.UTC)
                + (d[5] % 1.0);
        if (c.aggregate() == SqlOp.STDDEV_POP || c.aggregate() == SqlOp.STDDEV_SAMP)
          variance(s, secs);
        else numeric(c, s, secs);
      }
      default -> {}
    }
  }

  private Object aggregateResult(Plan.Column c, Summary s, FieldDefn dst) {
    switch (c.aggregate()) {
      case AVG -> {
        if (s.count == 0) return null;
        double avg = s.sum() / s.count;
        if (c.fieldType().isDateLike()) {
          long secs = (long) Math.floor(avg);
          LocalDateTime t =
              LocalDateTime.ofEpochSecond(
                  secs, (int) Math.round((avg - secs) * 1e9), ZoneOffset.UTC);
          return Values.assign(dst, t);
        }
        return Values.assign(dst, avg);
      }
      case SUM -> {
        return s.count == 0 ? null : Values.assign(dst, s.sum());
      }
      case MIN -> {
        if (s.count == 0) return null;
        boolean text = c.fieldType().isDateLike() || c.fieldType() == SqlType.STRING;
        return Values.assign(dst, text ? (Object) s.osMin : (Object) s.min);
      }
      case MAX -> {
        if (s.count == 0) return null;
        boolean text = c.fieldType().isDateLike() || c.fieldType() == SqlType.STRING;
        return Values.assign(dst, text ? (Object) s.osMax : (Object) s.max);
      }
      case STDDEV_POP -> {
        return s.count > 0 ? Values.assign(dst, Math.sqrt(s.sqDist / s.count)) : null;
      }
      case STDDEV_SAMP -> {
        return s.count > 1 ? Values.assign(dst, Math.sqrt(s.sqDist / (s.count - 1))) : null;
      }
      default -> {
        return null;
      }
    }
  }
}
