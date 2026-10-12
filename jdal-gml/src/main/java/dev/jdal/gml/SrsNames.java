package dev.jdal.gml;

import dev.jdal.srs.SpatialReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@code srsName} of GML geometries: which CRS it names and whether the coordinates come
 * in the order of the CRS definition (latitude first for geographic CRS) or as x, y.
 *
 * <p>The URN and URL forms ({@code urn:ogc:def:crs:EPSG::4326}, {@code
 * http://www.opengis.net/def/crs/EPSG/0/4326}) follow the axis order of the EPSG definition; {@code
 * EPSG:4326} and the old {@code .../srs/epsg.xml#4326} form mean longitude first. This
 * implementation knows the axis order only for geographic CRS (north first) and a few projected
 * ones; other CRS are taken as east, north.
 */
public record SrsNames(String name, int epsg, boolean axisOrderFromDefinition) {

  private static final Pattern URN =
      Pattern.compile("^urn:(?:x-)?ogc:def:crs:EPSG:[^:]*:(\\d+)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern URL =
      Pattern.compile(
          "^https?://www\\.opengis\\.net/def/crs/EPSG/[^/]+/(\\d+)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern OLD_URL =
      Pattern.compile(
          "^https?://www\\.opengis\\.net/gml/srs/epsg\\.xml#(\\d+)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern SHORT = Pattern.compile("^EPSG:(\\d+)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern COMPOUND_FIRST =
      Pattern.compile(
          "^urn:(?:x-)?ogc:def:crs,crs:EPSG:[^:]*:(\\d+),.*$", Pattern.CASE_INSENSITIVE);

  /** Projected CRS defined with northing first. */
  private static final java.util.Set<Integer> NORTH_FIRST_PROJECTED =
      java.util.Set.of(
          2193, 3035, 3034, 3041, 3042, 3043, 3044, 3045, 3046, 3047, 3048, 3049, 3050, 3051);

  /** Parses an {@code srsName}; unknown forms give epsg 0. */
  public static SrsNames parse(String srsName) {
    if (srsName == null) return new SrsNames(null, 0, false);
    String s = srsName.strip();
    if (s.equalsIgnoreCase("urn:ogc:def:crs:OGC:1.3:CRS84")
        || s.equalsIgnoreCase("urn:ogc:def:crs:OGC::CRS84")
        || s.equalsIgnoreCase("http://www.opengis.net/def/crs/OGC/1.3/CRS84")) {
      return new SrsNames(s, 4326, false);
    }
    Matcher m;
    if ((m = URN.matcher(s)).matches()
        || (m = URL.matcher(s)).matches()
        || (m = COMPOUND_FIRST.matcher(s)).matches()) {
      return new SrsNames(s, Integer.parseInt(m.group(1)), true);
    }
    if ((m = OLD_URL.matcher(s)).matches() || (m = SHORT.matcher(s)).matches()) {
      return new SrsNames(s, Integer.parseInt(m.group(1)), false);
    }
    return new SrsNames(s, 0, false);
  }

  /** The CRS, or null if it is not known. */
  public SpatialReference srs() {
    if (epsg <= 0) return null;
    try {
      return SpatialReference.fromEpsg(epsg);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** True if the coordinates are written north (or latitude) first and must be swapped to x, y. */
  public boolean swapsAxes() {
    if (!axisOrderFromDefinition || epsg <= 0) return false;
    if (NORTH_FIRST_PROJECTED.contains(epsg)) return true;
    SpatialReference srs = srs();
    return srs != null && srs.isGeographic();
  }
}
