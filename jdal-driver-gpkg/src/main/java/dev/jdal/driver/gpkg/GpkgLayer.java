package dev.jdal.driver.gpkg;

import dev.jdal.core.*;
import dev.jdal.feature.*;
import dev.jdal.geom.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Stream;

/** A GeoPackage feature table. */
final class GpkgLayer implements Layer {

  private final GpkgDataset dataset;
  private final Connection conn;
  private final String table;
  private final String fidColumn;
  private final String geomColumn;
  private final int srsId;
  private volatile FeatureType type;

  /** Column name of each attribute field, in field order. */
  private final List<String> fieldColumns;

  private Envelope extent;
  private boolean extentDirty;

  private GpkgLayer(
      GpkgDataset dataset,
      Connection conn,
      String table,
      String fidColumn,
      String geomColumn,
      int srsId,
      FeatureType type,
      List<String> fieldColumns,
      Envelope extent) {
    this.dataset = dataset;
    this.conn = conn;
    this.table = table;
    this.fidColumn = fidColumn;
    this.geomColumn = geomColumn;
    this.srsId = srsId;
    this.type = type;
    this.fieldColumns = new ArrayList<>(fieldColumns);
    this.extent = extent;
  }

  static String q(String identifier) {
    return '"' + identifier.replace("\"", "\"\"") + '"';
  }

  static String lit(String s) {
    return "'" + s.replace("'", "''") + "'";
  }

  // ------------------------------------------------------------------ open / create

  static GpkgLayer open(GpkgDataset ds, Connection conn, String table) throws SQLException {
    String geomCol = null, geomTypeName = "GEOMETRY";
    int srsId = 0, z = 0, m = 0;
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT column_name, geometry_type_name, srs_id, z, m FROM gpkg_geometry_columns WHERE"
                + " table_name = ?")) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          geomCol = rs.getString(1);
          geomTypeName = rs.getString(2);
          srsId = rs.getInt(3);
          z = rs.getInt(4);
          m = rs.getInt(5);
        }
      }
    }
    Envelope extent = null;
    String identifier = table;
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT min_x, min_y, max_x, max_y FROM gpkg_contents WHERE table_name = ?")) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          double a = rs.getDouble(1);
          boolean has = !rs.wasNull();
          double b = rs.getDouble(2), c = rs.getDouble(3), d = rs.getDouble(4);
          if (has) extent = new Envelope(a, b, c, d);
        }
      }
    }
    String fid = null;
    List<FieldDefn> fields = new ArrayList<>();
    List<String> columns = new ArrayList<>();
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("PRAGMA table_info(" + q(table) + ")")) {
      while (rs.next()) {
        String name = rs.getString("name");
        String declared = rs.getString("type");
        boolean notNull = rs.getInt("notnull") != 0;
        String dflt = rs.getString("dflt_value");
        boolean pk = rs.getInt("pk") != 0;
        if (name.equals(geomCol)) continue;
        if (pk
            && fid == null
            && declared != null
            && declared.toUpperCase(Locale.ROOT).startsWith("INT")) {
          fid = name;
          continue;
        }
        fields.add(GpkgTypes.fieldFor(name, declared).withNullable(!notNull).withDefault(dflt));
        columns.add(name);
      }
    }
    if (fid == null) fid = "rowid";
    List<GeomFieldDefn> geoms =
        geomCol == null
            ? List.of()
            : List.of(
                new GeomFieldDefn(
                    geomCol,
                    GpkgDataset.geometryType(geomTypeName),
                    GpkgDataset.dimension(z, m),
                    ds.readSrs(srsId),
                    true));
    FeatureType type = new FeatureType(table, fields, geoms);
    return new GpkgLayer(ds, conn, table, fid, geomCol, srsId, type, columns, extent);
  }

  static GpkgLayer create(
      GpkgDataset ds, Connection conn, FeatureType requested, Map<String, String> options)
      throws SQLException {
    Map<String, String> opt = new HashMap<>();
    options.forEach((k, v) -> opt.put(k.toUpperCase(Locale.ROOT), v));
    String table = requested.name();
    String fid = opt.getOrDefault("FID", "fid");
    GeomFieldDefn g = requested.geomFields().isEmpty() ? null : requested.geomFields().getFirst();
    String geomName =
        opt.getOrDefault("GEOMETRY_NAME", g != null && !g.name().isEmpty() ? g.name() : "geom");
    int srs = g == null ? 0 : ds.ensureSrs(g.srs());

    StringBuilder sql =
        new StringBuilder(
            "CREATE TABLE "
                + q(table)
                + " ("
                + q(fid)
                + " INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL");
    if (g != null) sql.append(", ").append(q(geomName)).append(" BLOB");
    List<String> columns = new ArrayList<>();
    List<FieldDefn> fields = new ArrayList<>();
    for (FieldDefn f : requested.fields()) {
      sql.append(", ").append(q(f.name())).append(' ').append(GpkgTypes.sqlType(f));
      if (!f.nullable()) sql.append(" NOT NULL");
      if (f.unique()) sql.append(" UNIQUE");
      if (f.defaultValue() != null) sql.append(" DEFAULT ").append(f.defaultValue());
      columns.add(f.name());
      fields.add(f);
    }
    sql.append(')');
    conn.setAutoCommit(false);
    try (Statement s = conn.createStatement()) {
      s.execute(sql.toString());
    }
    String identifier = opt.getOrDefault("IDENTIFIER", table);
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO gpkg_contents (table_name, data_type, identifier, srs_id) VALUES (?,"
                + " 'features', ?, ?)")) {
      ps.setString(1, table);
      ps.setString(2, identifier);
      ps.setInt(3, srs);
      ps.executeUpdate();
    }
    List<GeomFieldDefn> geoms = List.of();
    if (g != null) {
      Dimension d = g.dimension();
      try (PreparedStatement ps =
          conn.prepareStatement("INSERT INTO gpkg_geometry_columns VALUES (?, ?, ?, ?, ?, ?)")) {
        ps.setString(1, table);
        ps.setString(2, geomName);
        ps.setString(
            3,
            g.type() == GeometryType.UNKNOWN
                ? "GEOMETRY"
                : g.type().displayName().toUpperCase(Locale.ROOT));
        ps.setInt(4, srs);
        ps.setInt(5, d.hasZ() ? 1 : 0);
        ps.setInt(6, d.hasM() ? 1 : 0);
        ps.executeUpdate();
      }
      geoms = List.of(new GeomFieldDefn(geomName, g.type(), g.dimension(), g.srs(), true));
    }
    return new GpkgLayer(
        ds,
        conn,
        table,
        fid,
        g == null ? null : geomName,
        srs,
        new FeatureType(table, fields, geoms),
        columns,
        null);
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
            Capability.RANDOM_READ,
            Capability.FAST_FEATURE_COUNT,
            Capability.TRANSACTIONS,
            Capability.Z_GEOMETRIES,
            Capability.MEASURED_GEOMETRIES);
    if (extent != null) c.add(Capability.FAST_EXTENT);
    if (dataset.writable()) {
      c.addAll(
          EnumSet.of(
              Capability.SEQUENTIAL_WRITE,
              Capability.RANDOM_WRITE,
              Capability.CREATE_FIELD,
              Capability.DELETE_FEATURE));
    }
    return c;
  }

  private String selectList() {
    StringBuilder sb = new StringBuilder(q(fidColumn));
    if (geomColumn != null) sb.append(", ").append(q(geomColumn));
    for (String c : fieldColumns) sb.append(", ").append(q(c));
    return sb.toString();
  }

  private Stream<Feature> query(String where, Object param, long limit) {
    String sql =
        "SELECT "
            + selectList()
            + " FROM "
            + q(table)
            + (where == null ? "" : " WHERE " + where)
            + " ORDER BY "
            + q(fidColumn)
            + (limit >= 0 ? " LIMIT " + limit : "");
    try {
      PreparedStatement ps = conn.prepareStatement(sql);
      if (param != null) ps.setObject(1, param);
      ResultSet rs = ps.executeQuery();
      FeatureType t = type;
      Iterator<Feature> it =
          new Iterator<>() {
            boolean fetched, has;

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
                return readRow(rs, t);
              } catch (SQLException e) {
                throw new JdalException("Read failed: " + e.getMessage(), e);
              }
            }
          };
      return java.util.stream.StreamSupport.stream(
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
      throw new JdalException("Query failed: " + e.getMessage(), e);
    }
  }

  private Feature readRow(ResultSet rs, FeatureType t) throws SQLException {
    long fid = rs.getLong(1);
    int col = 2;
    Geometry g = null;
    if (geomColumn != null) {
      byte[] blob = rs.getBytes(col++);
      if (blob != null) {
        try {
          g = GpkgGeometry.decode(blob);
        } catch (IllegalArgumentException e) {
          throw new JdalException(
              "Corrupt geometry in " + table + " fid " + fid + ": " + e.getMessage(), e);
        }
      }
    }
    List<Object> values = new ArrayList<>(fieldColumns.size());
    for (FieldDefn f : t.fields()) {
      Object v = rs.getObject(col++);
      values.add(v == null ? null : FieldValues.coerce(f, v));
    }
    List<Geometry> geoms = geomColumn == null ? List.of() : java.util.Collections.singletonList(g);
    return new Feature(t, fid, values, geoms);
  }

  @Override
  public Stream<Feature> features() {
    return query(null, null, -1);
  }

  @Override
  public Stream<Feature> features(Query query) {
    if (query.isUnrestricted()) return features();
    if (query.where() != null)
      return Layer.super.features(query); // attribute filters are evaluated by the SQL engine
    if (query.bbox() == null && query.filter() == null && query.spatial() == null)
      return query(null, null, query.limit());
    Stream<Feature> s = features().filter(query::matches);
    return query.limit() >= 0 ? s.limit(query.limit()) : s;
  }

  @Override
  public long count() {
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + q(table))) {
      return rs.next() ? rs.getLong(1) : 0;
    } catch (SQLException e) {
      throw new JdalException("Count failed: " + e.getMessage(), e);
    }
  }

  @Override
  public Optional<Envelope> extent() {
    if (extent != null && !extent.isEmpty()) return Optional.of(extent);
    return Layer.super.extent();
  }

  @Override
  public Optional<Feature> feature(long fid) {
    try (Stream<Feature> s = query(q(fidColumn) + " = ?", fid, -1)) {
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
    switch (v) {
      case Integer i -> ps.setInt(idx, i);
      case Long l -> ps.setLong(idx, l);
      case Double d -> ps.setDouble(idx, d);
      case byte[] b -> ps.setBytes(idx, b);
      case OffsetDateTime o ->
          ps.setString(
              idx,
              o.withOffsetSameInstant(ZoneOffset.UTC).toString().replaceAll(":00Z$", ":00.000Z"));
      case LocalDateTime t -> ps.setString(idx, t.toString());
      case List<?> l -> ps.setString(idx, GpkgTypes.listToJson(l));
      default -> ps.setString(idx, v.toString());
    }
  }

  @Override
  public synchronized Feature insert(Feature f) {
    checkWritable();
    checkShape(f);
    try {
      begin();
      StringBuilder cols = new StringBuilder(), marks = new StringBuilder();
      if (f.hasFid()) {
        cols.append(q(fidColumn));
        marks.append('?');
      }
      if (geomColumn != null) {
        if (cols.length() > 0) {
          cols.append(", ");
          marks.append(", ");
        }
        cols.append(q(geomColumn));
        marks.append('?');
      }
      for (String c : fieldColumns) {
        if (cols.length() > 0) {
          cols.append(", ");
          marks.append(", ");
        }
        cols.append(q(c));
        marks.append('?');
      }
      String sql =
          cols.length() == 0
              ? "INSERT INTO " + q(table) + " DEFAULT VALUES"
              : "INSERT INTO " + q(table) + " (" + cols + ") VALUES (" + marks + ")";
      long fid;
      try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
        int i = 1;
        if (f.hasFid()) ps.setLong(i++, f.fid());
        if (geomColumn != null) bindGeometry(ps, i++, f.geometry());
        for (int k = 0; k < fieldColumns.size(); k++) bind(ps, i++, type.fields().get(k), f.get(k));
        ps.executeUpdate();
        fid = f.hasFid() ? f.fid() : lastInsertRowid();
      }
      return new Feature(type, fid, f.values(), f.geometries());
    } catch (SQLException e) {
      throw new JdalException("Insert failed: " + e.getMessage(), e);
    }
  }

  private long lastInsertRowid() throws SQLException {
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private void bindGeometry(PreparedStatement ps, int idx, Geometry g) throws SQLException {
    if (g == null) {
      ps.setNull(idx, Types.NULL);
      return;
    }
    ps.setBytes(idx, GpkgGeometry.encode(g, srsId));
    if (!g.isEmpty()) {
      Envelope e = g.envelope();
      extent = extent == null ? e : extent.union(e);
      extentDirty = true;
    }
  }

  @Override
  public synchronized void update(Feature f) {
    checkWritable();
    checkShape(f);
    try {
      begin();
      StringBuilder set = new StringBuilder();
      if (geomColumn != null) set.append(q(geomColumn)).append(" = ?");
      for (String c : fieldColumns) {
        if (set.length() > 0) set.append(", ");
        set.append(q(c)).append(" = ?");
      }
      if (set.length() == 0) return;
      try (PreparedStatement ps =
          conn.prepareStatement(
              "UPDATE " + q(table) + " SET " + set + " WHERE " + q(fidColumn) + " = ?")) {
        int i = 1;
        if (geomColumn != null) bindGeometry(ps, i++, f.geometry());
        for (int k = 0; k < fieldColumns.size(); k++) bind(ps, i++, type.fields().get(k), f.get(k));
        ps.setLong(i, f.fid());
        if (ps.executeUpdate() == 0)
          throw new JdalException("No feature with fid " + f.fid() + " in " + table);
      }
    } catch (SQLException e) {
      throw new JdalException("Update failed: " + e.getMessage(), e);
    }
  }

  @Override
  public synchronized boolean delete(long fid) {
    checkWritable();
    try {
      begin();
      try (PreparedStatement ps =
          conn.prepareStatement("DELETE FROM " + q(table) + " WHERE " + q(fidColumn) + " = ?")) {
        ps.setLong(1, fid);
        return ps.executeUpdate() > 0;
      }
    } catch (SQLException e) {
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
                + q(table)
                + " ADD COLUMN "
                + q(field.name())
                + " "
                + GpkgTypes.sqlType(field));
      }
      fieldColumns.add(field.name());
      type = type.addField(field);
    } catch (SQLException e) {
      throw new JdalException("Cannot add field: " + e.getMessage(), e);
    }
  }

  private void checkWritable() {
    if (!dataset.writable()) throw new JdalException("GeoPackage opened read-only");
  }

  private void checkShape(Feature f) {
    if (f.values().size() != type.fields().size()
        || f.geometries().size() != type.geomFields().size()) {
      throw new JdalException("Feature does not fit layer " + table);
    }
  }

  @Override
  public synchronized void sync() {
    if (!dataset.writable()) return;
    try {
      if (extentDirty && extent != null) {
        try (PreparedStatement ps =
            conn.prepareStatement(
                "UPDATE gpkg_contents SET min_x=?, min_y=?, max_x=?, max_y=?, "
                    + "last_change=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE table_name=?")) {
          ps.setDouble(1, extent.minX());
          ps.setDouble(2, extent.minY());
          ps.setDouble(3, extent.maxX());
          ps.setDouble(4, extent.maxY());
          ps.setString(5, table);
          ps.executeUpdate();
        }
        extentDirty = false;
      }
      if (!conn.getAutoCommit()) conn.commit();
    } catch (SQLException e) {
      throw new JdalException("Sync failed: " + e.getMessage(), e);
    }
  }
}
