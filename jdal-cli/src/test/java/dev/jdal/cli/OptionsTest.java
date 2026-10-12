package dev.jdal.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.core.Jdal;
import dev.jdal.core.OptionDef;
import dev.jdal.testkit.Autotest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OptionsTest {

  @Test
  void unknownKeysAndInvalidValuesAreReported() {
    List<OptionDef> defs = Jdal.driver("CSV").orElseThrow().layerCreationOptions();
    assertEquals(
        List.of(), OptionDef.problems("layer creation", "CSV", defs, Map.of("separator", "tab")));
    List<String> p =
        OptionDef.problems("layer creation", "CSV", defs, Map.of("FOO", "1", "SEPARATOR", "XX"));
    assertEquals(2, p.size());
    assertTrue(p.stream().anyMatch(m -> m.contains("does not support FOO layer creation option")));
    assertTrue(p.stream().anyMatch(m -> m.contains("not valid for option SEPARATOR")));
  }

  @Test
  void everyCreatingDriverDeclaresOptionsConsistently() {
    for (var d : Jdal.drivers()) {
      for (OptionDef o :
          java.util.stream.Stream.of(
                  d.openOptions(), d.datasetCreationOptions(), d.layerCreationOptions())
              .flatMap(List::stream)
              .toList()) {
        if (o.defaultValue() != null && !o.values().isEmpty()) {
          assertTrue(o.check(o.defaultValue()).isEmpty(), d.name() + " " + o.name());
        }
      }
    }
  }

  @Test
  void keyValueAndEnvelopeConversion() throws Exception {
    var kv = new Converters.KeyValueConverter().convert(input("A=b=c"));
    assertEquals(new Converters.KeyValue("A", "b=c"), kv);
    assertThrows(Exception.class, () -> new Converters.KeyValueConverter().convert(input("A")));
    assertEquals(3.0, new Converters.EnvelopeConverter().convert(input("0,1,3,4")).maxX());
    assertThrows(Exception.class, () -> new Converters.EnvelopeConverter().convert(input("0,1,3")));
  }

  @Test
  void layerNamesOfASource() {
    assertEquals(
        List.of("poly"), Converters.layerNames(Autotest.data("ogr/data/poly.shp").toString()));
    assertEquals(List.of(), Converters.layerNames("/does/not/exist.shp"));
  }

  private static org.aesh.command.converter.ConverterInvocation input(String s) {
    return new org.aesh.command.converter.ConverterInvocation() {
      @Override
      public String getInput() {
        return s;
      }

      @Override
      public org.aesh.console.AeshContext getAeshContext() {
        return null;
      }
    };
  }
}
