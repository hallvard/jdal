package dev.jdal.testkit;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;

/**
 * Locates GDAL's {@code autotest} directory ({@code -Dgdal.autotest=...}). Tests are skipped if it
 * is missing.
 */
public final class Autotest {

  private Autotest() {}

  /**
   * A fresh, unique directory path in {@code /vsimem/} (like GDAL's {@code tmp_vsimem} fixture).
   */
  public static String vsimem() {
    return "/vsimem/" + java.util.UUID.randomUUID();
  }

  public static Path root() {
    String p =
        System.getProperty("gdal.autotest", System.getProperty("user.home") + "/git/gdal/autotest");
    return Path.of(p);
  }

  /** Resolves a path below autotest, skipping the calling test if it does not exist. */
  public static Path data(String relative) {
    Path p = root().resolve(relative);
    Assumptions.assumeTrue(Files.exists(p), "missing " + p);
    return p;
  }

  /**
   * Like {@link #data} but for use outside tests (e.g. {@code @MethodSource}); returns null if
   * missing.
   */
  public static Path dataOrNull(String relative) {
    Path p = root().resolve(relative);
    return Files.exists(p) ? p : null;
  }
}
