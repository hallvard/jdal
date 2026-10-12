package dev.jdal.driver.gpkg;

import dev.jdal.core.Dataset;
import dev.jdal.core.Driver;
import dev.jdal.core.JdalException;
import dev.jdal.core.OpenInfo;
import dev.jdal.core.OptionDef;
import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiMode;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public final class GpkgDriver implements Driver {

  private static final byte[] SQLITE_MAGIC =
      "SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  private static final int APPLICATION_ID = 0x47504B47; // "GPKG"
  private static final int APPLICATION_ID_GP10 = 0x47503130;
  private static final int APPLICATION_ID_GP11 = 0x47503131;

  @Override
  public String name() {
    return "GPKG";
  }

  @Override
  public String description() {
    return "GeoPackage";
  }

  @Override
  public List<String> extensions() {
    return List.of("gpkg");
  }

  @Override
  public boolean identify(OpenInfo info) {
    if (!info.isFile()) return false;
    byte[] h = info.header();
    if (h.length < 100) return false;
    for (int i = 0; i < SQLITE_MAGIC.length; i++) if (h[i] != SQLITE_MAGIC[i]) return false;
    int appId = ByteBuffer.wrap(h, 68, 4).getInt();
    return appId == APPLICATION_ID
        || appId == APPLICATION_ID_GP10
        || appId == APPLICATION_ID_GP11
        || (info.extension().equals("gpkg") && ByteBuffer.wrap(h, 60, 4).getInt() >= 10200);
  }

  @Override
  public Dataset open(String location, boolean update, Map<String, String> options) {
    Vsi vsi = Vsi.global();
    Path staged = null;
    try {
      if (vsi.isLocal(location)) {
        return new GpkgDataset(this, location, connect(location), update);
      }
      // SQLite needs a real file: work on a copy and write it back when closed
      staged = Files.createTempFile("jdal-", ".gpkg");
      try (var in = vsi.newInputStream(location)) {
        Files.copy(in, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      GpkgDataset ds = new GpkgDataset(this, location, connect(staged.toString()), update);
      ds.afterClose(stagedCleanup(vsi, location, staged, update));
      return ds;
    } catch (SQLException | java.io.IOException e) {
      deleteQuietly(staged);
      throw new JdalException("Cannot open GeoPackage " + location + ": " + e.getMessage(), e);
    }
  }

  @Override
  public boolean canCreate() {
    return true;
  }

  @Override
  public Dataset create(String location, Map<String, String> options) {
    Vsi vsi = Vsi.global();
    Path staged = null;
    try {
      GpkgDataset ds;
      if (vsi.isLocal(location)) {
        Files.deleteIfExists(Path.of(location));
        ds = new GpkgDataset(this, location, connect(location), true);
      } else {
        staged = Files.createTempFile("jdal-", ".gpkg");
        ds = new GpkgDataset(this, location, connect(staged.toString()), true);
        ds.afterClose(stagedCleanup(vsi, location, staged, true));
      }
      ds.initialize();
      return ds;
    } catch (SQLException | java.io.IOException e) {
      deleteQuietly(staged);
      throw new JdalException("Cannot create GeoPackage " + location + ": " + e.getMessage(), e);
    }
  }

  /** Copies the staged database to {@code location} (if it may have changed) and removes it. */
  private static Runnable stagedCleanup(Vsi vsi, String location, Path staged, boolean write) {
    return () -> {
      try {
        if (write) {
          try (var out = vsi.newOutputStream(location, VsiMode.WRITE)) {
            Files.copy(staged, out);
          }
        }
      } catch (java.io.IOException e) {
        throw new JdalException("Cannot write GeoPackage " + location + ": " + e.getMessage(), e);
      } finally {
        deleteQuietly(staged);
      }
    };
  }

  private static void deleteQuietly(Path p) {
    try {
      if (p != null) Files.deleteIfExists(p);
    } catch (java.io.IOException e) {
      // a stray temporary file is not worth failing for
    }
  }

  private static Connection connect(String location) throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + location);
  }

  @Override
  public List<OptionDef> layerCreationOptions() {
    return List.of(
        OptionDef.string("FID", "fid", "Name of the integer primary key column"),
        OptionDef.string("GEOMETRY_NAME", "geom", "Name of the geometry column"),
        OptionDef.string("IDENTIFIER", "Layer identifier, default the table name"));
  }
}
