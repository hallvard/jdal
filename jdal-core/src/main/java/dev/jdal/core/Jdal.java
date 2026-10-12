package dev.jdal.core;

import dev.jdal.vsi.Vsi;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/** Entry point: driver registry and {@code open}/{@code create} (GDAL's {@code GDALOpenEx}). */
public final class Jdal {

  private Jdal() {}

  private static final class Holder {
    static final List<Driver> DRIVERS =
        ServiceLoader.load(Driver.class).stream().map(ServiceLoader.Provider::get).toList();
  }

  public static List<Driver> drivers() {
    return Holder.DRIVERS;
  }

  public static Optional<Driver> driver(String name) {
    return drivers().stream().filter(d -> d.name().equalsIgnoreCase(name)).findFirst();
  }

  public static Dataset open(String location) {
    return open(location, false, Map.of());
  }

  public static Dataset open(String location, boolean update, Map<String, String> options) {
    location = Vsi.expandHome(location);
    OpenInfo info = new OpenInfo(location, options);
    for (Driver d : drivers()) {
      boolean ok;
      try {
        ok = d.identify(info);
      } catch (RuntimeException e) {
        ok = false;
      }
      if (ok) return d.open(location, update, options);
    }
    if (info.isPath() && !Vsi.global().exists(location)) {
      String where =
          location.startsWith("/") || location.startsWith("/vsi")
              ? ""
              : " (relative to " + System.getProperty("user.dir") + ")";
      throw new JdalException("No such file or directory: " + location + where);
    }
    throw new JdalException("No driver recognises '" + location + "'");
  }

  public static Dataset open(
      String driverName, String location, boolean update, Map<String, String> options) {
    location = Vsi.expandHome(location);
    return driver(driverName)
        .orElseThrow(() -> new JdalException("Unknown driver " + driverName))
        .open(location, update, options);
  }

  public static Dataset create(String driverName, String location, Map<String, String> options) {
    location = Vsi.expandHome(location);
    Driver d =
        driver(driverName).orElseThrow(() -> new JdalException("Unknown driver " + driverName));
    if (!d.canCreate()) throw new JdalException("Driver " + driverName + " cannot create datasets");
    return d.create(location, options);
  }

  /** Guesses a writing driver from the file extension. */
  public static Optional<Driver> driverForOutput(String location) {
    location = Vsi.expandHome(location);
    OpenInfo info = new OpenInfo(location, Map.of());
    String ext = info.extension();
    Optional<Driver> byExtension =
        drivers().stream()
            .filter(Driver::canCreate)
            .filter(d -> d.extensions().contains(ext))
            .findFirst();
    if (byExtension.isPresent() || info.isPath()) return byExtension;
    // connection strings such as "PG:..." are recognised by the driver itself
    return drivers().stream().filter(Driver::canCreate).filter(d -> d.identify(info)).findFirst();
  }
}
