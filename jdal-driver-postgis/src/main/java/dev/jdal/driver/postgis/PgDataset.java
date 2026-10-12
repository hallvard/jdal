package dev.jdal.driver.postgis;

import dev.jdal.core.*;
import dev.jdal.feature.FeatureType;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class PgDataset implements Dataset {

  private final PgDriver driver;
  private final String location;
  private final Connection conn;
  private final String schema;
  private final boolean writable;
  private final List<PgLayer> layers = new ArrayList<>();

  PgDataset(PgDriver driver, String location, Connection conn, String schema, boolean writable)
      throws SQLException {
    this.driver = driver;
    this.location = location;
    this.conn = conn;
    this.schema = schema;
    this.writable = writable;
    loadLayers();
  }

  private void loadLayers() throws SQLException {
    List<String> tables = new ArrayList<>();
    try (var ps =
        conn.prepareStatement(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type"
                + " IN ('BASE TABLE','VIEW') AND table_name NOT IN"
                + " ('spatial_ref_sys','geometry_columns','geography_columns','raster_columns','raster_overviews')"
                + " ORDER BY table_name")) {
      ps.setString(1, schema);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) tables.add(rs.getString(1));
      }
    }
    for (String t : tables) layers.add(PgLayer.open(this, conn, schema, t));
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
    if (!writable) throw new JdalException("Dataset opened read-only");
    boolean overwrite =
        options.entrySet().stream()
            .anyMatch(
                e ->
                    e.getKey().equalsIgnoreCase("OVERWRITE")
                        && e.getValue().equalsIgnoreCase("YES"));
    String laundered = PgLayer.launder(type.name());
    boolean exists = layer(type.name()).isPresent() || layer(laundered).isPresent();
    if (exists && !overwrite) throw new JdalException("Layer " + type.name() + " already exists");
    if (exists)
      layers.removeIf(
          l -> l.name().equalsIgnoreCase(type.name()) || l.name().equalsIgnoreCase(laundered));
    try {
      PgLayer l = PgLayer.create(this, conn, schema, type, options);
      layers.add(l);
      return l;
    } catch (SQLException e) {
      rollbackQuietly();
      throw new JdalException("Cannot create layer " + type.name() + ": " + e.getMessage(), e);
    }
  }

  @Override
  public synchronized void deleteLayer(String name) {
    PgLayer l = (PgLayer) requireLayer(name);
    try (Statement s = conn.createStatement()) {
      s.execute("DROP TABLE " + PgLayer.q(schema) + "." + PgLayer.q(l.name()) + " CASCADE");
      if (!conn.getAutoCommit()) conn.commit();
      layers.remove(l);
    } catch (SQLException e) {
      rollbackQuietly();
      throw new JdalException("Cannot delete layer " + name + ": " + e.getMessage(), e);
    }
  }

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
    return "PostgreSQL";
  }

  private Layer nativeSql(String sql) {
    try (Statement s = conn.createStatement()) {
      if (!s.execute(sql)) {
        if (!conn.getAutoCommit()) conn.commit();
        return null;
      }
      try (ResultSet rs = s.getResultSet()) {
        return PgSqlResult.toLayer(rs);
      }
    } catch (SQLException e) {
      rollbackQuietly();
      throw new JdalException("SQL failed: " + e.getMessage(), e);
    }
  }

  void rollbackQuietly() {
    try {
      if (!conn.getAutoCommit()) conn.rollback();
    } catch (SQLException ignored) {
      // connection is unusable anyway
    }
  }

  @Override
  public synchronized void close() {
    try {
      for (PgLayer l : layers) l.sync();
      if (!conn.getAutoCommit()) conn.commit();
      conn.close();
    } catch (SQLException e) {
      throw new JdalException("Cannot close connection: " + e.getMessage(), e);
    }
  }
}
