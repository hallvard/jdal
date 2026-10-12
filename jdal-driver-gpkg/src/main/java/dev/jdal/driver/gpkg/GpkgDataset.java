package dev.jdal.driver.gpkg;

import dev.jdal.core.*;
import dev.jdal.feature.*;
import dev.jdal.geom.Dimension;
import dev.jdal.geom.GeometryType;
import dev.jdal.srs.SpatialReference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class GpkgDataset implements Dataset {

  private final GpkgDriver driver;
  private final String location;
  private final Connection conn;
  private final boolean writable;
  private final List<GpkgLayer> layers = new ArrayList<>();
  private Runnable afterClose;

  GpkgDataset(GpkgDriver driver, String location, Connection conn, boolean writable)
      throws SQLException {
    this.driver = driver;
    this.location = location;
    this.conn = conn;
    this.writable = writable;
    if (!isNew()) loadLayers();
  }

  /** Runs once the connection is closed: copies a staged file back to its virtual location. */
  void afterClose(Runnable action) {
    this.afterClose = action;
  }

  private boolean isNew() throws SQLException {
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT 1 FROM sqlite_master WHERE name='gpkg_contents'")) {
      return !rs.next();
    }
  }

  /** Creates the GeoPackage system tables. */
  void initialize() throws SQLException {
    try (Statement s = conn.createStatement()) {
      s.execute("PRAGMA application_id = 1196444487");
      s.execute("PRAGMA user_version = 10400");
      s.execute(
          "CREATE TABLE gpkg_spatial_ref_sys (srs_name TEXT NOT NULL, srs_id INTEGER PRIMARY KEY,"
              + " organization TEXT NOT NULL, organization_coordsys_id INTEGER NOT NULL, definition"
              + " TEXT NOT NULL, description TEXT)");
      s.execute(
          "CREATE TABLE gpkg_contents (table_name TEXT NOT NULL PRIMARY KEY, data_type TEXT NOT"
              + " NULL, identifier TEXT UNIQUE, description TEXT DEFAULT '', last_change DATETIME"
              + " NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')), min_x DOUBLE, min_y"
              + " DOUBLE, max_x DOUBLE, max_y DOUBLE, srs_id INTEGER, CONSTRAINT fk_gc_r_srs_id"
              + " FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id))");
      s.execute(
          "CREATE TABLE gpkg_geometry_columns (table_name TEXT NOT NULL, column_name TEXT NOT NULL,"
              + " geometry_type_name TEXT NOT NULL, srs_id INTEGER NOT NULL, z TINYINT NOT NULL, m"
              + " TINYINT NOT NULL, CONSTRAINT pk_geom_cols PRIMARY KEY (table_name, column_name),"
              + " CONSTRAINT fk_gc_tn FOREIGN KEY (table_name) REFERENCES"
              + " gpkg_contents(table_name), CONSTRAINT fk_gc_srs FOREIGN KEY (srs_id) REFERENCES"
              + " gpkg_spatial_ref_sys(srs_id))");
      s.execute(
          "INSERT INTO gpkg_spatial_ref_sys VALUES ('Undefined Cartesian SRS', -1, 'NONE', -1,"
              + " 'undefined', 'undefined Cartesian coordinate reference system')");
      s.execute(
          "INSERT INTO gpkg_spatial_ref_sys VALUES ('Undefined geographic SRS', 0, 'NONE', 0,"
              + " 'undefined', 'undefined geographic coordinate reference system')");
    }
    ensureSrs(SpatialReference.WGS84);
  }

  /** Makes sure {@code srs} has a row in gpkg_spatial_ref_sys and returns its srs_id. */
  int ensureSrs(SpatialReference srs) throws SQLException {
    if (srs == null) return 0;
    int id =
        srs.epsg() > 0 ? srs.epsg() : 100000 + Math.floorMod(srs.definition().hashCode(), 800000);
    try (PreparedStatement ps =
        conn.prepareStatement("SELECT 1 FROM gpkg_spatial_ref_sys WHERE srs_id = ?")) {
      ps.setInt(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return id;
      }
    }
    try (PreparedStatement ps =
        conn.prepareStatement("INSERT INTO gpkg_spatial_ref_sys VALUES (?,?,?,?,?,NULL)")) {
      ps.setString(1, srs.epsg() > 0 ? "EPSG:" + srs.epsg() : srs.definition());
      ps.setInt(2, id);
      ps.setString(3, srs.epsg() > 0 ? "EPSG" : "NONE");
      ps.setInt(4, srs.epsg() > 0 ? srs.epsg() : id);
      ps.setString(5, srs.toWkt().orElse(srs.proj4() != null ? srs.proj4() : "undefined"));
      ps.executeUpdate();
    }
    return id;
  }

  SpatialReference readSrs(int srsId) throws SQLException {
    if (srsId <= 0) return null;
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT organization, organization_coordsys_id, definition FROM gpkg_spatial_ref_sys"
                + " WHERE srs_id = ?")) {
      ps.setInt(1, srsId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return null;
        String org = rs.getString(1);
        int code = rs.getInt(2);
        String def = rs.getString(3);
        if ("EPSG".equalsIgnoreCase(org)) return SpatialReference.fromEpsg(code);
        try {
          return SpatialReference.fromUserInput(def);
        } catch (IllegalArgumentException e) {
          return null;
        }
      }
    }
  }

  private void loadLayers() throws SQLException {
    List<String> tables = new ArrayList<>();
    try (Statement s = conn.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT table_name FROM gpkg_contents WHERE data_type = 'features' ORDER BY"
                    + " rowid")) {
      while (rs.next()) tables.add(rs.getString(1));
    }
    for (String t : tables) layers.add(GpkgLayer.open(this, conn, t));
  }

  Connection connection() {
    return conn;
  }

  boolean writable() {
    return writable;
  }

  @Override
  public String name() {
    return location;
  }

  @Override
  public Driver driver() {
    return driver;
  }

  @Override
  public synchronized List<Layer> layers() {
    return List.copyOf(layers);
  }

  @Override
  public boolean canCreateLayers() {
    return writable;
  }

  @Override
  public synchronized Layer createLayer(FeatureType type, Map<String, String> options) {
    if (!writable) throw new JdalException("GeoPackage opened read-only");
    if (layer(type.name()).isPresent())
      throw new JdalException("Layer " + type.name() + " already exists");
    try {
      GpkgLayer l = GpkgLayer.create(this, conn, type, options);
      layers.add(l);
      return l;
    } catch (SQLException e) {
      throw new JdalException("Cannot create layer " + type.name() + ": " + e.getMessage(), e);
    }
  }

  @Override
  public synchronized void deleteLayer(String name) {
    GpkgLayer l = (GpkgLayer) requireLayer(name);
    try (Statement s = conn.createStatement()) {
      s.execute("DROP TABLE " + GpkgLayer.q(l.name()));
      s.execute("DELETE FROM gpkg_geometry_columns WHERE table_name = " + GpkgLayer.lit(l.name()));
      s.execute("DELETE FROM gpkg_contents WHERE table_name = " + GpkgLayer.lit(l.name()));
      layers.remove(l);
    } catch (SQLException e) {
      throw new JdalException("Cannot delete layer " + name + ": " + e.getMessage(), e);
    }
  }

  /**
   * Runs a SELECT and exposes the result as an in-memory layer (statements without result return
   * null).
   */
  @Override
  public Layer executeSql(String sql, dev.jdal.geom.Geometry spatialFilter, String dialect) {
    if (dialect != null && dialect.equalsIgnoreCase("OGRSQL")) return ogrSql(sql, spatialFilter);
    if (dialect != null && !dialect.isEmpty() && !dialect.equalsIgnoreCase(nativeDialect())) {
      return Dataset.super.executeSql(sql, spatialFilter, dialect);
    }
    Layer result = nativeSql(sql);
    if (spatialFilter != null && result != null) {
      dev.jdal.core.Diagnostics.warn("Spatial filter is ignored for native SQL");
    }
    return result;
  }

  private String nativeDialect() {
    return "SQLITE";
  }

  private Layer nativeSql(String sql) {
    try (Statement s = conn.createStatement()) {
      if (!s.execute(sql)) return null;
      try (ResultSet rs = s.getResultSet()) {
        return SqlResult.toLayer(rs, this);
      }
    } catch (SQLException e) {
      throw new JdalException("SQL failed: " + e.getMessage(), e);
    }
  }

  @Override
  public synchronized void close() {
    try {
      for (GpkgLayer l : layers) l.sync();
      if (!conn.getAutoCommit()) conn.commit();
      conn.close();
    } catch (SQLException e) {
      throw new JdalException("Cannot close GeoPackage: " + e.getMessage(), e);
    } finally {
      if (afterClose != null) {
        Runnable run = afterClose;
        afterClose = null;
        run.run();
      }
    }
  }

  static Dimension dimension(int z, int m) {
    return Dimension.of(z > 0, m > 0);
  }

  static GeometryType geometryType(String name) {
    try {
      return GeometryType.fromName(name);
    } catch (IllegalArgumentException e) {
      return GeometryType.UNKNOWN;
    }
  }
}
