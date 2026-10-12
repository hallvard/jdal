package dev.jdal.driver.postgis;

import dev.jdal.core.Layer;
import dev.jdal.core.memory.MemoryDataset;
import dev.jdal.feature.*;
import dev.jdal.geom.Dimension;
import dev.jdal.geom.Geometry;
import dev.jdal.geom.GeometryType;
import dev.jdal.geom.Wkb;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;

/** Materializes the result of an arbitrary query as an in-memory layer called "sql_statement". */
final class PgSqlResult {

  private PgSqlResult() {}

  static Layer toLayer(ResultSet rs) throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    int n = md.getColumnCount();
    int geomCol = -1;
    List<FieldDefn> fields = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
      String tn = md.getColumnTypeName(i);
      if (geomCol < 0 && tn.equalsIgnoreCase("geometry")) {
        geomCol = i;
        continue;
      }
      fields.add(PgTypes.fieldFor(md.getColumnLabel(i), tn, 0, md.getPrecision(i), md.getScale(i)));
    }
    List<GeomFieldDefn> geoms =
        geomCol < 0
            ? List.of()
            : List.of(
                new GeomFieldDefn(
                    md.getColumnLabel(geomCol), GeometryType.UNKNOWN, Dimension.XY, null, true));
    FeatureType type = new FeatureType("sql_statement", fields, geoms);
    Layer layer = new MemoryDataset("sql").createLayer(type, Map.of());
    while (rs.next()) {
      Feature.Builder b = Feature.builder(type);
      int fi = 0;
      for (int i = 1; i <= n; i++) {
        if (i == geomCol) {
          String hex = rs.getString(i);
          if (hex != null) b.geometry(readHex(hex));
        } else {
          b.set(fi, PgLayer.readValue(rs, i, fields.get(fi), md.getColumnTypeName(i)));
          fi++;
        }
      }
      layer.insert(b.build());
    }
    return layer;
  }

  /** pgjdbc returns geometry values as hex-encoded EWKB text. */
  static Geometry readHex(String hex) {
    return Wkb.read(HexFormat.of().parseHex(hex));
  }
}
