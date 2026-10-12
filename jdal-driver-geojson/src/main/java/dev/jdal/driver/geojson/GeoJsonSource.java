package dev.jdal.driver.geojson;

import dev.jdal.core.JdalException;
import dev.jdal.vsi.Vsi;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

/**
 * Where a GeoJSON document comes from; can be opened repeatedly (the layer reads the file once per
 * scan).
 */
interface GeoJsonSource {

  Reader open();

  static GeoJsonSource ofFile(String file) {
    return () -> {
      try {
        return Vsi.global().newReader(file);
      } catch (IOException e) {
        throw new JdalException("Cannot read " + file + ": " + e.getMessage(), e);
      }
    };
  }

  static GeoJsonSource ofText(String text) {
    return () -> new StringReader(text);
  }
}
