package dev.jdal.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.Dataset;
import dev.jdal.core.Jdal;
import dev.jdal.core.JdalException;
import dev.jdal.utils.Translate;
import dev.jdal.utils.TranslateOptions;
import dev.jdal.vsi.Vsi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Locations typed by hand (in the interactive mode no shell expands {@code ~}). */
class LocationTest {

  @TempDir Path home;
  String savedHome;

  @BeforeEach
  void fakeHome() throws Exception {
    savedHome = System.getProperty("user.home");
    System.setProperty("user.home", home.toString());
    Files.writeString(home.resolve("cities.csv"), "name,pop\nOslo,700000\n");
  }

  @AfterEach
  void restoreHome() {
    System.setProperty("user.home", savedHome);
  }

  @Test
  void aLeadingTildeIsTheHomeDirectory() throws Exception {
    assertEquals(home + "/a/b.gpkg", Vsi.expandHome("~/a/b.gpkg"));
    assertEquals(home.toString(), Vsi.expandHome("~"));
    assertEquals("~other/x", Vsi.expandHome("~other/x")); // another user's home is not supported
    assertEquals("/a/~/b", Vsi.expandHome("/a/~/b"));
    assertEquals("PG:dbname=x", Vsi.expandHome("PG:dbname=x"));
    try (Dataset ds = Jdal.open("~/cities.csv")) {
      assertEquals(1, ds.layers().getFirst().count());
    }
    assertTrue(Vsi.global().exists("~/cities.csv"));
    long n = Translate.translate("~/cities.csv", "~/out.geojson", TranslateOptions.defaults());
    assertEquals(1, n);
    assertTrue(Files.exists(home.resolve("out.geojson")));
    try (Dataset ds = Jdal.create("GeoJSON", "~/created.geojson", Map.of())) {
      assertNotNull(ds);
    }
  }

  @Test
  void aMissingFileIsNotReportedAsAnUnknownFormat() throws Exception {
    JdalException missing = assertThrows(JdalException.class, () -> Jdal.open("~/nope.gpkg"));
    assertEquals("No such file or directory: " + home + "/nope.gpkg", missing.getMessage());
    JdalException relative = assertThrows(JdalException.class, () -> Jdal.open("nope.gpkg"));
    assertTrue(
        relative.getMessage().startsWith("No such file or directory: nope.gpkg (relative to "),
        relative.getMessage());
    Files.writeString(home.resolve("data.bin"), "just some text");
    JdalException unknown = assertThrows(JdalException.class, () -> Jdal.open("~/data.bin"));
    assertTrue(unknown.getMessage().startsWith("No driver recognises"), unknown.getMessage());
  }
}
