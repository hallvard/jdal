package dev.jdal.srs;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;

/**
 * An immutable coordinate reference system. At least one of {@code epsg}, {@code proj4} or {@code
 * wkt} is set. Coordinates are always (x, y) = (easting/longitude, northing/latitude), i.e. GIS
 * axis order.
 *
 * @param epsg EPSG code or 0 if unknown
 * @param wkt WKT definition as given by the source, or null
 * @param proj4 PROJ string, or null
 */
public record SpatialReference(int epsg, String wkt, String proj4) {

  public static final SpatialReference WGS84 = fromEpsg(4326);

  private static final CRSFactory FACTORY = new CRSFactory();
  private static final Pattern EPSG_NAME = Pattern.compile("^(?:EPSG|epsg):(\\d+)$");
  private static final Pattern EPSG_URN =
      Pattern.compile("^urn:ogc:def:crs:EPSG:[^:]*:(\\d+)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern EPSG_URL =
      Pattern.compile("^https?://www\\.opengis\\.net/def/crs/EPSG/0/(\\d+)$");
  private static final Pattern WKT_AUTH =
      Pattern.compile("AUTHORITY\\s*\\[\\s*\"EPSG\"\\s*,\\s*\"?(\\d+)\"?\\s*\\]\\s*\\]\\s*$");
  private static final Pattern WKT2_ID =
      Pattern.compile("ID\\s*\\[\\s*\"EPSG\"\\s*,\\s*(\\d+)\\s*\\]\\s*\\]\\s*$");

  public SpatialReference {
    if (epsg == 0 && wkt == null && proj4 == null) {
      throw new IllegalArgumentException(
          "A spatial reference needs an EPSG code, WKT or PROJ string");
    }
  }

  public static SpatialReference fromEpsg(int code) {
    return new SpatialReference(code, null, null);
  }

  /** Parses "EPSG:n", an OGC URN/URL, a PROJ string ("+proj=..."), or WKT (WKT1 or WKT2). */
  public static SpatialReference fromUserInput(String text) {
    String t = text.strip();
    for (Pattern p : new Pattern[] {EPSG_NAME, EPSG_URN, EPSG_URL}) {
      Matcher m = p.matcher(t);
      if (m.matches()) return fromEpsg(Integer.parseInt(m.group(1)));
    }
    if (t.startsWith("+") || t.regionMatches(true, 0, "proj4:", 0, 6)) {
      String s = t.regionMatches(true, 0, "proj4:", 0, 6) ? t.substring(6).strip() : t;
      return new SpatialReference(0, null, s);
    }
    String upper = t.toUpperCase(Locale.ROOT);
    if (upper.startsWith("GEOGCS")
        || upper.startsWith("PROJCS")
        || upper.startsWith("GEOGCRS")
        || upper.startsWith("PROJCRS")
        || upper.startsWith("COMPD_CS")
        || upper.startsWith("BOUNDCRS")) {
      return fromWkt(t);
    }
    throw new IllegalArgumentException("Cannot interpret spatial reference: " + text);
  }

  public static SpatialReference fromWkt(String wkt) {
    String t = wkt.strip();
    Matcher m = WKT_AUTH.matcher(t);
    boolean found = m.find();
    if (!found) {
      m = WKT2_ID.matcher(t);
      found = m.find();
    }
    int code = found ? Integer.parseInt(m.group(1)) : 0;
    if (code == 0) {
      // no authority: identify well-known CRS by name, e.g. an ESRI .prj file
      java.util.regex.Matcher nm =
          java.util.regex.Pattern.compile(
                  "^\\s*(?:PROJCS|GEOGCS|PROJCRS|GEOGCRS)\\s*\\[\\s*\"([^\"]*)\"")
              .matcher(t);
      if (nm.find()) code = WellKnownCrs.identifyByName(nm.group(1)).orElse(0);
    }
    return new SpatialReference(code, wkt, null);
  }

  /**
   * WKT1 (OGC flavour with authorities): the original WKT if there is one, else generated for
   * well-known EPSG codes.
   */
  public java.util.Optional<String> toWkt() {
    if (wkt != null) return java.util.Optional.of(wkt);
    return epsg > 0
        ? java.util.Optional.ofNullable(WellKnownCrs.wkt(epsg, false))
        : java.util.Optional.empty();
  }

  /**
   * WKT1 as written to ESRI .prj files; generated for well-known EPSG codes, else the original WKT
   * if any.
   */
  public java.util.Optional<String> toEsriWkt() {
    if (epsg > 0) {
      String generated = WellKnownCrs.wkt(epsg, true);
      if (generated != null) return java.util.Optional.of(generated);
    }
    return java.util.Optional.ofNullable(wkt);
  }

  /** Short identifier: "EPSG:4326" or the PROJ string or the WKT. */
  public String definition() {
    if (epsg > 0) return "EPSG:" + epsg;
    return proj4 != null ? proj4 : wkt;
  }

  /** True if both describe the same CRS: same EPSG code, or else the same definition text. */
  public boolean isSame(SpatialReference o) {
    if (epsg > 0 && o.epsg > 0) return epsg == o.epsg;
    return definition().equals(o.definition());
  }

  /**
   * The proj4j CRS for transformations. Fails for WKT without an EPSG code and without a PROJ
   * string.
   */
  CoordinateReferenceSystem toProj() {
    if (epsg > 0) return FACTORY.createFromName("EPSG:" + epsg);
    if (proj4 != null) return FACTORY.createFromParameters(null, proj4);
    throw new UnsupportedOperationException(
        "Cannot transform WKT-only CRS without EPSG identifier");
  }

  public boolean isGeographic() {
    try {
      return toProj().getProjection().getName().equals("longlat")
          || toProj().getProjection().isGeographic();
    } catch (UnsupportedOperationException e) {
      return wkt != null && wkt.strip().toUpperCase(Locale.ROOT).startsWith("GEOG");
    }
  }
}
