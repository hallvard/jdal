package dev.jdal.driver.gpkg;

import dev.jdal.core.Layer;
import dev.jdal.core.memory.MemoryDataset;
import dev.jdal.feature.*;
import dev.jdal.geom.Dimension;
import dev.jdal.geom.GeometryType;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;

/** Materializes a JDBC result set as an in-memory layer called "SELECT". */
final class SqlResult {

  private SqlResult() {}

  static Layer toLayer(ResultSet rs, GpkgDataset ds) throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    int n = md.getColumnCount();
    List<Object[]> rows = new ArrayList<>();
    while (rs.next()) {
      Object[] row = new Object[n];
      for (int i = 0; i < n; i++) row[i] = rs.getObject(i + 1);
      rows.add(row);
    }
    int geomCol = -1;
    for (int i = 0; i < n && geomCol < 0; i++) {
      for (Object[] r : rows) {
        if (r[i] instanceof byte[] b) {
          if (b.length > 8 && b[0] == 'G' && b[1] == 'P') geomCol = i;
          break;
        }
      }
    }
    List<FieldDefn> fields = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      if (i == geomCol) continue;
      FieldType t = FieldType.STRING;
      for (Object[] r : rows) {
        Object v = r[i];
        if (v == null) continue;
        t =
            v instanceof Integer || v instanceof Long
                ? FieldType.INTEGER64
                : v instanceof Number
                    ? FieldType.REAL
                    : v instanceof byte[] ? FieldType.BINARY : FieldType.STRING;
        break;
      }
      fields.add(FieldDefn.of(md.getColumnLabel(i + 1), t));
    }
    List<GeomFieldDefn> geoms =
        geomCol < 0
            ? List.of()
            : List.of(
                new GeomFieldDefn(
                    md.getColumnLabel(geomCol + 1),
                    GeometryType.UNKNOWN,
                    Dimension.XY,
                    null,
                    true));
    FeatureType type = new FeatureType("SELECT", fields, geoms);
    Layer layer = new MemoryDataset("sql").createLayer(type, Map.of());
    for (Object[] r : rows) {
      Feature.Builder b = Feature.builder(type);
      int fi = 0;
      for (int i = 0; i < n; i++) {
        if (i == geomCol) {
          if (r[i] != null) b.geometry(GpkgGeometry.decode((byte[]) r[i]));
        } else {
          b.set(fi++, r[i]);
        }
      }
      layer.insert(b.build());
    }
    return layer;
  }
}
