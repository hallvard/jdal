package dev.jdal.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

  @TempDir Path dir;

  @Test
  void valuesComeFromTheEnvFile() throws IOException {
    Files.writeString(
        dir.resolve(".env"),
        """
        # a comment
        GDAL_HTTP_USERAGENT=jdal-test
        GDAL_HTTP_HEADERS = X-A: 1
        QUOTED="with spaces"
        SINGLE='x y'
        export EXPORTED=yes
        PATH_VALUE=C:\\\\data
        EMPTY=
        """);
    Config c = Config.of(dir, k -> null);
    assertEquals("jdal-test", c.get("GDAL_HTTP_USERAGENT"));
    assertEquals("X-A: 1", c.get("GDAL_HTTP_HEADERS"));
    assertEquals("with spaces", c.get("QUOTED"));
    assertEquals("x y", c.get("SINGLE"));
    assertEquals("yes", c.get("EXPORTED"));
    assertEquals("C:\\data", c.get("PATH_VALUE"));
    assertEquals("", c.get("EMPTY"));
    assertNull(c.get("MISSING"));
    assertEquals("dflt", c.get("MISSING", "dflt"));
  }

  @Test
  void systemPropertiesThenEnvironmentThenFile() throws IOException {
    Files.writeString(dir.resolve(".env"), "A=file\nB=file\nC=file\n");
    Config c = Config.of(dir, Map.of("B", "env", "A", "env")::get);
    System.setProperty("jdal.config.test.A", "property");
    try {
      Files.writeString(dir.resolve(".env"), "jdal.config.test.A=file\nB=file\nC=file\n");
      Config d = Config.of(dir, Map.of("B", "env", "jdal.config.test.A", "env")::get);
      assertEquals("property", d.get("jdal.config.test.A"));
      assertEquals("env", d.get("B"));
      assertEquals("file", d.get("C"));
    } finally {
      System.clearProperty("jdal.config.test.A");
    }
    assertEquals("env", c.get("A"));
  }

  @Test
  void noFileIsFine() {
    Config c = Config.of(dir, k -> null);
    assertNull(c.get("ANYTHING"));
  }

  @Test
  void theGlobalConfigurationReadsTheCurrentDirectory() {
    assertNotNull(Config.global());
    assertEquals(System.getenv("HOME"), Config.global().get("HOME")); // the environment is visible
  }
}
