package dev.jdal.driver.postgis;

import dev.jdal.core.Dataset;
import dev.jdal.core.Driver;
import dev.jdal.core.JdalException;
import dev.jdal.core.OpenInfo;
import dev.jdal.core.OptionDef;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL with PostGIS. Locations look like {@code PG:host=localhost dbname=gis user=me
 * password=secret}.
 */
public final class PgDriver implements Driver {

  @Override
  public String name() {
    return "PostgreSQL";
  }

  @Override
  public String description() {
    return "PostgreSQL / PostGIS";
  }

  @Override
  public boolean identify(OpenInfo info) {
    return PgConnection.isPgLocation(info.location());
  }

  @Override
  public Dataset open(String location, boolean update, Map<String, String> options) {
    return connect(location, update);
  }

  /**
   * "Creating" a PostgreSQL dataset connects to an existing database; layers are then created in
   * it.
   */
  @Override
  public boolean canCreate() {
    return true;
  }

  @Override
  public Dataset create(String location, Map<String, String> options) {
    return connect(location, true);
  }

  private Dataset connect(String location, boolean update) {
    PgConnection pc = PgConnection.parse(location);
    try {
      Connection c = DriverManager.getConnection(pc.jdbcUrl(), pc.properties());
      return new PgDataset(this, location, c, pc.schema(), update);
    } catch (SQLException e) {
      throw new JdalException("Cannot connect to PostgreSQL: " + e.getMessage(), e);
    }
  }

  @Override
  public List<OptionDef> layerCreationOptions() {
    return List.of(
        OptionDef.bool("OVERWRITE", false, "Drop an existing table with the same name"),
        OptionDef.bool("LAUNDER", true, "Lower-case and sanitize table and column names"),
        OptionDef.string("FID", "ogc_fid", "Name of the serial primary key column"),
        OptionDef.string("GEOMETRY_NAME", "wkb_geometry", "Name of the geometry column"),
        OptionDef.choice("SPATIAL_INDEX", "GIST", "Kind of spatial index", "GIST", "NONE"));
  }
}
