package dev.jdal.driver.postgis;

import dev.jdal.core.*;
import dev.jdal.feature.*;
import dev.jdal.geom.*;
import dev.jdal.srs.SpatialReference;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** A PostgreSQL table or view, optionally with a PostGIS geometry column. */
final class PgLayer implements Layer {

  private final PgDataset dataset;
  private final Connection conn;
  private final String schema;
  private final String table;
  private final String fidColumn; // null for views/tables without integer primary key
  private final String geomColumn; // null if no geometry
  private final int srid;
  private volatile FeatureType type;
  private final List<String> fieldColumns;
  private final List<String> pgTypes;

  private PgLayer(
      PgDataset dataset,
      Connection conn,
      String schema,
      String table,
      String fidColumn,
      String geomColumn,
      int srid,
      FeatureType type,
      List<String> fieldColumns,
      List<String> pgTypes) {
    this.dataset = dataset;
    this.conn = conn;
    this.schema = schema;
    this.table = table;
    this.fidColumn = fidColumn;
    this.geomColumn = geomColumn;
    this.srid = srid;
    this.type = type;
    this.fieldColumns = new ArrayList<>(fieldColumns);
    this.pgTypes = new ArrayList<>(pgTypes);
  }

  static String q(String identifier) {
    return '"' + identifier.replace("\"", "\"\"") + '"';
  }

  private String qualified() {
    return q(schema) + "." + q(table);
  }

  /** GDAL's default identifier laundering: lower case, only letters, digits and underscore. */
  static String launder(String name) {
    String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    return s.isEmpty() || Character.isDigit(s.charAt(0)) ? "_" + s : s;
  }

  // ------------------------------------------------------------------ open / create

  static PgLayer open(PgDataset ds, Connection conn, String schema, String table)
      throws SQLException {
    String geomCol = null, geomTypeName = "GEOMETRY";
    int srid = 0, coordDim = 2;
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT f_geometry_column, coord_dimension, srid, type FROM geometry_columns WHERE"
                + " f_table_schema = ? AND f_table_name = ? ORDER BY 1")) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          geomCol = rs.getString(1);
          coordDim = rs.getInt(2);
          srid = rs.getInt(3);
          geomTypeName = rs.getString(4);
        }
      }
    } catch (SQLException e) {
      // PostGIS not installed: plain tables only
      if (!conn.getAutoCommit()) conn.rollback();
    }
    String fid = primaryKey(conn, schema, table);
    List<FieldDefn> fields = new ArrayList<>();
    List<String> columns = new ArrayList<>();
    List<String> types = new ArrayList<>();
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT column_name, udt_name, character_maximum_length, numeric_precision,"
                + " numeric_scale, is_nullable, column_default FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position")) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String name = rs.getString(1), udt = rs.getString(2);
          if (name.equals(geomCol) || name.equals(fid)) continue;
          if (udt.equals("geometry") || udt.equals("geography")) continue;
          FieldDefn f =
              PgTypes.fieldFor(name, udt, rs.getInt(3), rs.getInt(4), rs.getInt(5))
                  .withNullable("YES".equals(rs.getString(6)))
                  .withDefault(rs.getString(7));
          fields.add(f);
          columns.add(name);
          types.add(udt);
        }
      }
    }
    List<GeomFieldDefn> geoms =
        geomCol == null
            ? List.of()
            : List.of(
                new GeomFieldDefn(
                    geomCol,
                    PgTypes.geometryType(geomTypeName),
                    PgTypes.dimension(coordDim, geomTypeName),
                    srid > 0 ? SpatialReference.fromEpsg(srid) : null,
                    true));
    FeatureType ft = new FeatureType(table, fields, geoms);
    return new PgLayer(ds, conn, schema, table, fid, geomCol, srid, ft, columns, types);
  }

  private static String primaryKey(Connection conn, String schema, String table)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT a.attname, format_type(a.atttypid, a.atttypmod) FROM pg_index i JOIN"
                + " pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) WHERE"
                + " i.indrelid = ?::regclass AND i.indisprimary")) {
      ps.setString(1, q(schema) + "." + q(table));
      try (ResultSet rs = ps.executeQuery()) {
        String name = null;
        int n = 0;
        while (rs.next()) {
          n++;
          String t = rs.getString(2);
          if (t.equals("integer") || t.equals("bigint") || t.equals("smallint"))
            name = rs.getString(1);
        }
        return n == 1 ? name : null;
      }
    } catch (SQLException e) {
      if (!conn.getAutoCommit()) conn.rollback();
      return null; // views have no primary key (regclass lookup of a view still works; other
      // failures are ignored)
    }
  }

  static PgLayer create(
      PgDataset ds,
      Connection conn,
      String schema,
      FeatureType requested,
      Map<String, String> options)
      throws SQLException {
    Map<String, String> opt = new HashMap<>();
    options.forEach((k, v) -> opt.put(k.toUpperCase(Locale.ROOT), v));
    boolean launder = !"NO".equalsIgnoreCase(opt.getOrDefault("LAUNDER", "YES"));
    String table = launder ? launder(requested.name()) : requested.name();
    String fid = opt.getOrDefault("FID", "ogc_fid");
    GeomFieldDefn g = requested.geomFields().isEmpty() ? null : requested.geomFields().getFirst();
    String geomName =
        opt.getOrDefault(
            "GEOMETRY_NAME", g != null && !g.name().isEmpty() ? g.name() : "wkb_geometry");
    if (launder) geomName = launder(geomName);
    int srid = g != null && g.srs() != null && g.srs().epsg() > 0 ? g.srs().epsg() : 0;

    conn.setAutoCommit(false);
    if ("YES".equalsIgnoreCase(opt.getOrDefault("OVERWRITE", "NO"))) {
      try (Statement s = conn.createStatement()) {
        s.execute("DROP TABLE IF EXISTS " + q(schema) + "." + q(table) + " CASCADE");
      }
    }
    StringBuilder sql =
        new StringBuilder(
            "CREATE TABLE " + q(schema) + "." + q(table) + " (" + q(fid) + " SERIAL PRIMARY KEY");
    if (g != null) {
      sql.append(", ")
          .append(q(geomName))
          .append(" geometry(")
          .append(PgTypes.geometryTypeClause(g.type(), g.dimension()))
          .append(',')
          .append(srid)
          .append(')');
    }
    List<FieldDefn> fields = new ArrayList<>();
    List<String> columns = new ArrayList<>();
    List<String> types = new ArrayList<>();
    for (FieldDefn f : requested.fields()) {
      FieldDefn nf = launder ? f.withName(launder(f.name())) : f;
      sql.append(", ").append(q(nf.name())).append(' ').append(PgTypes.sqlType(nf));
      if (!nf.nullable()) sql.append(" NOT NULL");
      if (nf.unique()) sql.append(" UNIQUE");
      if (nf.defaultValue() != null) sql.append(" DEFAULT ").append(nf.defaultValue());
      fields.add(nf);
      columns.add(nf.name());
      types.add(PgTypes.sqlType(nf));
    }
    sql.append(')');
    try (Statement s = conn.createStatement()) {
      s.execute(sql.toString());
      if (g != null && !"NONE".equalsIgnoreCase(opt.getOrDefault("SPATIAL_INDEX", "GIST"))) {
        s.execute(
            "CREATE INDEX "
                + q(table + "_" + geomName + "_geom_idx")
                + " ON "
                + q(schema)
                + "."
                + q(table)
                + " USING GIST ("
                + q(geomName)
                + ")");
      }
    }
    List<GeomFieldDefn> geoms =
        g == null
            ? List.of()
            : List.of(new GeomFieldDefn(geomName, g.type(), g.dimension(), g.srs(), true));
    return new PgLayer(
        ds,
        conn,
        schema,
        table,
        fid,
        g == null ? null : geomName,
        srid,
        new FeatureType(table, fields, geoms),
        columns,
        types);
  }

  // ------------------------------------------------------------------ reading

  @Override
  public String name() {
    return table;
  }

  @Override
  public FeatureType type() {
    return type;
  }

  @Override
  public Set<Capability> capabilities() {
    EnumSet<Capability> c =
        EnumSet.of(
            Capability.FAST_FEATURE_COUNT,
            Capability.TRANSACTIONS,
            Capability.Z_GEOMETRIES,
            Capability.MEASURED_GEOMETRIES);
    if (geomColumn != null)
      c.addAll(EnumSet.of(Capability.FAST_SPATIAL_FILTER, Capability.FAST_EXTENT));
    if (fidColumn != null) c.add(Capability.RANDOM_READ);
    if (dataset.writable()) {
      c.addAll(EnumSet.of(Capability.SEQUENTIAL_WRITE, Capability.CREATE_FIELD));
      if (fidColumn != null)
        c.addAll(EnumSet.of(Capability.RANDOM_WRITE, Capability.DELETE_FEATURE));
    }
    return c;
  }

  private String selectList() {
    StringBuilder sb = new StringBuilder(fidColumn != null ? q(fidColumn) : "0");
    sb.append(geomColumn != null ? ", ST_AsEWKB(" + q(geomColumn) + ")" : "");
    for (String c : fieldColumns) sb.append(", ").append(q(c));
    return sb.toString();
  }

  private Stream<Feature> select(String where, List<Object> params, long limit) {
    String sql =
        "SELECT "
            + selectList()
            + " FROM "
            + qualified()
            + (where == null ? "" : " WHERE " + where)
            + (fidColumn != null ? " ORDER BY " + q(fidColumn) : "")
            + (limit >= 0 ? " LIMIT " + limit : "");
    try {
      PreparedStatement ps = conn.prepareStatement(sql);
      ps.setFetchSize(1000);
      for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
      ResultSet rs = ps.executeQuery();
      FeatureType t = type;
      Iterator<Feature> it =
          new Iterator<>() {
            boolean fetched, has;
            long seq;

            @Override
            public boolean hasNext() {
              if (!fetched) {
                try {
                  has = rs.next();
                } catch (SQLException e) {
                  throw new JdalException("Read failed: " + e.getMessage(), e);
                }
                fetched = true;
              }
              return has;
            }

            @Override
            public Feature next() {
              if (!hasNext()) throw new NoSuchElementException();
              fetched = false;
              try {
                return readRow(rs, t, fidColumn == null ? seq++ : -2);
              } catch (SQLException e) {
                throw new JdalException("Read failed: " + e.getMessage(), e);
              }
            }
          };
      return StreamSupport.stream(
              Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL),
              false)
          .onClose(
              () -> {
                try {
                  rs.close();
                  ps.close();
                } catch (SQLException e) {
                  throw new JdalException("Close failed: " + e.getMessage(), e);
                }
              });
    } catch (SQLException e) {
      dataset.rollbackQuietly();
      throw new JdalException("Query failed: " + e.getMessage(), e);
    }
  }

  private Feature readRow(ResultSet rs, FeatureType t, long syntheticFid) throws SQLException {
    long fid = syntheticFid == -2 ? rs.getLong(1) : syntheticFid;
    int col = 2;
    Geometry g = null;
    if (geomColumn != null) {
      byte[] ewkb = rs.getBytes(col++);
      if (ewkb != null) {
        try {
          g = Wkb.read(ewkb);
        } catch (IllegalArgumentException e) {
          throw new JdalException(
              "Corrupt geometry in " + table + " fid " + fid + ": " + e.getMessage(), e);
        }
      }
    }
    List<Object> values = new ArrayList<>(fieldColumns.size());
    for (int k = 0; k < fieldColumns.size(); k++) {
      FieldDefn f = t.fields().get(k);
      values.add(FieldValues.coerce(f, readValue(rs, col++, f, pgTypes.get(k))));
    }
    return new Feature(
        t, fid, values, geomColumn == null ? List.of() : Collections.singletonList(g));
  }

  /** Reads a column as the natural Java object for the field type. */
  static Object readValue(ResultSet rs, int i, FieldDefn f, String pgType) throws SQLException {
    Object v =
        switch (f.type()) {
          case DATE -> rs.getObject(i, LocalDate.class);
          case TIME -> rs.getObject(i, LocalTime.class);
          case DATETIME ->
              pgType.equalsIgnoreCase("timestamp")
                      || pgType.equalsIgnoreCase("timestamp without time zone")
                  ? rs.getObject(i, LocalDateTime.class)
                  : rs.getObject(i, OffsetDateTime.class);
          case BINARY -> rs.getBytes(i);
          case STRING -> rs.getString(i);
          case INTEGER_LIST, INTEGER64_LIST, REAL_LIST, STRING_LIST -> {
            Array a = rs.getArray(i);
            yield a == null ? null : Arrays.asList((Object[]) a.getArray());
          }
          default -> rs.getObject(i);
        };
    return rs.wasNull() ? null : v;
  }

  @Override
  public Stream<Feature> features() {
    return select(null, List.of(), -1);
  }

  @Override
  public Stream<Feature> features(Query query) {
    if (query.isUnrestricted()) return features();
    if (query.where() != null) {
      // attribute filters are evaluated by the SQL engine; the box part is still pushed down
      Stream<Feature> s = features(query.withWhere(null).withLimit(-1));
      Predicate<Feature> sql = SqlEngine.require().compileFilter(type, query.where());
      s = s.filter(sql);
      return query.limit() >= 0 ? s.limit(query.limit()) : s;
    }
    List<String> conditions = new ArrayList<>();
    List<Object> params = new ArrayList<>();
    if (query.bbox() != null && geomColumn != null) {
      Envelope b = query.bbox();
      conditions.add(q(geomColumn) + " && ST_MakeEnvelope(?, ?, ?, ?, " + srid + ")");
      params.addAll(List.of(b.minX(), b.minY(), b.maxX(), b.maxY()));
    }
    if (query.spatial() != null && geomColumn != null) {
      // PostGIS does the exact test; the filter geometry is taken to be in the layer's CRS
      SpatialFilter sf = query.spatial();
      conditions.add(
          "ST_"
              + postgisFunction(sf.op())
              + "("
              + q(geomColumn)
              + ", ST_GeomFromWKB(?, "
              + srid
              + "))");
      params.add(Wkb.write(sf.geometry()));
    }
    String where = conditions.isEmpty() ? null : String.join(" AND ", conditions);
    boolean javaFilter =
        query.filter() != null
            || ((query.bbox() != null || query.spatial() != null) && geomColumn == null);
    Stream<Feature> s = select(where, params, javaFilter ? -1 : query.limit());
    if (javaFilter) {
      s = s.filter(query::matches);
      if (query.limit() >= 0) s = s.limit(query.limit());
    }
    return s;
  }

  private static String postgisFunction(SpatialOp op) {
    return switch (op) {
      case INTERSECTS -> "Intersects";
      case WITHIN -> "Within";
      case CONTAINS -> "Contains";
      case DISJOINT -> "Disjoint";
      case TOUCHES -> "Touches";
      case CROSSES -> "Crosses";
      case OVERLAPS -> "Overlaps";
      case EQUALS -> "Equals";
    };
  }

  @Override
  public long count() {
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + qualified())) {
      return rs.next() ? rs.getLong(1) : 0;
    } catch (SQLException e) {
      dataset.rollbackQuietly();
      throw new JdalException("Count failed: " + e.getMessage(), e);
    }
  }

  @Override
  public Optional<Envelope> extent() {
    if (geomColumn == null) return Optional.empty();
    String sql =
        "SELECT ST_XMin(e), ST_YMin(e), ST_XMax(e), ST_YMax(e) FROM (SELECT ST_Extent("
            + q(geomColumn)
            + ") AS e FROM "
            + qualified()
            + ") q";
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      if (rs.next()) {
        double minX = rs.getDouble(1);
        if (!rs.wasNull())
          return Optional.of(new Envelope(minX, rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)));
      }
      return Optional.empty();
    } catch (SQLException e) {
      dataset.rollbackQuietly();
      throw new JdalException("Extent failed: " + e.getMessage(), e);
    }
  }

  @Override
  public Optional<Feature> feature(long fid) {
    if (fidColumn == null) return Layer.super.feature(fid);
    try (Stream<Feature> s = select(q(fidColumn) + " = ?", List.of(fid), -1)) {
      return s.findFirst();
    }
  }

  // ------------------------------------------------------------------ writing

  private void begin() throws SQLException {
    if (conn.getAutoCommit()) conn.setAutoCommit(false);
  }

  private void bind(PreparedStatement ps, int idx, FieldDefn f, Object v) throws SQLException {
    if (v == null) {
      ps.setNull(idx, Types.NULL);
      return;
    }
    if (f.type() == FieldType.INTEGER && f.subType() == FieldSubType.BOOLEAN) {
      ps.setBoolean(idx, ((Number) v).intValue() != 0);
    } else if (f.type().isList()) {
      ps.setArray(
          idx, conn.createArrayOf(PgTypes.arrayElementType(f.type()), ((List<?>) v).toArray()));
    } else if (v instanceof byte[] b) {
      ps.setBytes(idx, b);
    } else if (v instanceof Integer
        || v instanceof Long
        || v instanceof Double
        || v instanceof LocalDate
        || v instanceof LocalTime
        || v instanceof LocalDateTime
        || v instanceof OffsetDateTime) {
      if (v instanceof LocalDateTime && f.type() == FieldType.DATETIME) {
        ps.setObject(idx, ((LocalDateTime) v).atOffset(java.time.ZoneOffset.UTC));
      } else {
        ps.setObject(idx, v);
      }
    } else {
      ps.setString(idx, v.toString());
    }
  }

  @Override
  public synchronized Feature insert(Feature f) {
    checkWritable();
    checkShape(f);
    try {
      begin();
      List<String> cols = new ArrayList<>();
      List<String> marks = new ArrayList<>();
      if (f.hasFid() && fidColumn != null) {
        cols.add(q(fidColumn));
        marks.add("?");
      }
      if (geomColumn != null) {
        cols.add(q(geomColumn));
        marks.add("ST_GeomFromWKB(?, " + srid + ")");
      }
      for (String c : fieldColumns) {
        cols.add(q(c));
        marks.add("?");
      }
      String sql =
          cols.isEmpty()
              ? "INSERT INTO " + qualified() + " DEFAULT VALUES"
              : "INSERT INTO "
                  + qualified()
                  + " ("
                  + String.join(", ", cols)
                  + ") VALUES ("
                  + String.join(", ", marks)
                  + ")";
      if (fidColumn != null) sql += " RETURNING " + q(fidColumn);
      long fid = f.fid();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        int i = 1;
        if (f.hasFid() && fidColumn != null) ps.setLong(i++, f.fid());
        if (geomColumn != null) {
          if (f.geometry() == null) ps.setNull(i++, Types.BINARY);
          else ps.setBytes(i++, Wkb.write(f.geometry()));
        }
        for (int k = 0; k < fieldColumns.size(); k++) bind(ps, i++, type.fields().get(k), f.get(k));
        if (fidColumn != null) {
          try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) fid = rs.getLong(1);
          }
        } else {
          ps.executeUpdate();
        }
      }
      return new Feature(type, fid, f.values(), f.geometries());
    } catch (SQLException e) {
      dataset.rollbackQuietly();
      throw new JdalException("Insert failed: " + e.getMessage(), e);
    }
  }

  @Override
  public synchronized void update(Feature f) {
    checkWritable();
    checkShape(f);
    if (fidColumn == null) throw new JdalException(table + " has no integer primary key");
    try {
      begin();
      List<String> set = new ArrayList<>();
      if (geomColumn != null) set.add(q(geomColumn) + " = ST_GeomFromWKB(?, " + srid + ")");
      for (String c : fieldColumns) set.add(q(c) + " = ?");
      if (set.isEmpty()) return;
      try (PreparedStatement ps =
          conn.prepareStatement(
              "UPDATE "
                  + qualified()
                  + " SET "
                  + String.join(", ", set)
                  + " WHERE "
                  + q(fidColumn)
                  + " = ?")) {
        int i = 1;
        if (geomColumn != null) {
          if (f.geometry() == null) ps.setNull(i++, Types.BINARY);
          else ps.setBytes(i++, Wkb.write(f.geometry()));
        }
        for (int k = 0; k < fieldColumns.size(); k++) bind(ps, i++, type.fields().get(k), f.get(k));
        ps.setLong(i, f.fid());
        if (ps.executeUpdate() == 0)
          throw new JdalException("No feature with fid " + f.fid() + " in " + table);
      }
    } catch (SQLException e) {
      dataset.rollbackQuietly();
      throw new JdalException("Update failed: " + e.getMessage(), e);
    }
  }

  @Override
  public synchronized boolean delete(long fid) {
    checkWritable();
    if (fidColumn == null) throw new JdalException(table + " has no integer primary key");
    try {
      begin();
      try (PreparedStatement ps =
          conn.prepareStatement("DELETE FROM " + qualified() + " WHERE " + q(fidColumn) + " = ?")) {
        ps.setLong(1, fid);
        return ps.executeUpdate() > 0;
      }
    } catch (SQLException e) {
      dataset.rollbackQuietly();
      throw new JdalException("Delete failed: " + e.getMessage(), e);
    }
  }

  @Override
  public synchronized void addField(FieldDefn field) {
    checkWritable();
    try {
      begin();
      try (Statement s = conn.createStatement()) {
        s.execute(
            "ALTER TABLE "
                + qualified()
                + " ADD COLUMN "
                + q(field.name())
                + " "
                + PgTypes.sqlType(field));
      }
      fieldColumns.add(field.name());
      pgTypes.add(PgTypes.sqlType(field));
      type = type.addField(field);
    } catch (SQLException e) {
      dataset.rollbackQuietly();
      throw new JdalException("Cannot add field: " + e.getMessage(), e);
    }
  }

  private void checkWritable() {
    if (!dataset.writable()) throw new JdalException("Dataset opened read-only");
  }

  private void checkShape(Feature f) {
    if (f.values().size() != type.fields().size()
        || f.geometries().size() != type.geomFields().size()) {
      throw new JdalException("Feature does not fit layer " + table);
    }
  }

  @Override
  public synchronized void sync() {
    try {
      if (!conn.getAutoCommit()) conn.commit();
    } catch (SQLException e) {
      throw new JdalException("Commit failed: " + e.getMessage(), e);
    }
  }
}
