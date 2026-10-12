package dev.jdal.srs;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small table of common coordinate reference systems: identification of WKT by name when it
 * carries no authority, and generation of WKT1 (OGC and ESRI flavours) for their EPSG codes. proj4j
 * cannot produce WKT.
 */
final class WellKnownCrs {

  private WellKnownCrs() {}

  private record Geographic(
      int epsg,
      String name,
      String esriName,
      String datum,
      String esriDatum,
      int datumCode,
      String spheroid,
      String esriSpheroid,
      double a,
      String invF,
      int spheroidCode) {}

  private static final Map<Integer, Geographic> GEOGRAPHIC =
      Map.of(
          4326,
              new Geographic(
                  4326,
                  "WGS 84",
                  "GCS_WGS_1984",
                  "WGS_1984",
                  "D_WGS_1984",
                  6326,
                  "WGS 84",
                  "WGS_1984",
                  6378137,
                  "298.257223563",
                  7030),
          4269,
              new Geographic(
                  4269,
                  "NAD83",
                  "GCS_North_American_1983",
                  "North_American_Datum_1983",
                  "D_North_American_1983",
                  6269,
                  "GRS 1980",
                  "GRS_1980",
                  6378137,
                  "298.257222101",
                  7019),
          4267,
              new Geographic(
                  4267,
                  "NAD27",
                  "GCS_North_American_1927",
                  "North_American_Datum_1927",
                  "D_North_American_1927",
                  6267,
                  "Clarke 1866",
                  "Clarke_1866",
                  6378206.4,
                  "294.9786982",
                  7008),
          4258,
              new Geographic(
                  4258,
                  "ETRS89",
                  "GCS_ETRS_1989",
                  "European_Terrestrial_Reference_System_1989",
                  "D_ETRS_1989",
                  6258,
                  "GRS 1980",
                  "GRS_1980",
                  6378137,
                  "298.257222101",
                  7019),
          4230,
              new Geographic(
                  4230,
                  "ED50",
                  "GCS_European_1950",
                  "European_Datum_1950",
                  "D_European_1950",
                  6230,
                  "International 1924",
                  "International_1924",
                  6378388,
                  "297",
                  7022),
          4277,
              new Geographic(
                  4277,
                  "OSGB 1936",
                  "GCS_OSGB_1936",
                  "OSGB_1936",
                  "D_OSGB_1936",
                  6277,
                  "Airy 1830",
                  "Airy_1830",
                  6377563.396,
                  "299.3249646",
                  7001));

  private static final Pattern UTM_NAME =
      Pattern.compile(
          "(?i)^(WGS[ _]?(?:19)?84|NAD[ _]?(?:19)?83|ETRS[ _]?(?:19)?89)(?: / |_)UTM[ _]zone["
              + " _](\\d{1,2})([NS])$");

  // ------------------------------------------------------------------ identification

  /**
   * The EPSG code of the CRS whose top level name is {@code name}, if it is one of the known ones.
   */
  static Optional<Integer> identifyByName(String name) {
    String n = name.strip();
    for (Geographic g : GEOGRAPHIC.values()) {
      if (n.equalsIgnoreCase(g.name) || n.equalsIgnoreCase(g.esriName)) return Optional.of(g.epsg);
    }
    Matcher m = UTM_NAME.matcher(n);
    if (m.matches()) {
      int zone = Integer.parseInt(m.group(2));
      boolean north = m.group(3).equalsIgnoreCase("N");
      String datum =
          m.group(1)
              .toUpperCase(Locale.ROOT)
              .replaceAll("[ _]", "")
              .replace("1984", "84")
              .replace("1983", "83")
              .replace("1989", "89");
      if (zone < 1 || zone > 60) return Optional.empty();
      if (datum.startsWith("WGS")) return Optional.of((north ? 32600 : 32700) + zone);
      if (datum.startsWith("NAD") && north && zone >= 1 && zone <= 23)
        return Optional.of(26900 + zone);
      if (datum.startsWith("ETRS") && north && zone >= 28 && zone <= 38)
        return Optional.of(25800 + zone);
      return Optional.empty();
    }
    if (n.equalsIgnoreCase("OSGB 1936 / British National Grid")
        || n.equalsIgnoreCase("OSGB_1936_British_National_Grid")
        || n.equalsIgnoreCase("British_National_Grid")) {
      return Optional.of(27700);
    }
    if (n.equalsIgnoreCase("WGS 84 / Pseudo-Mercator")
        || n.equalsIgnoreCase("WGS_1984_Web_Mercator_Auxiliary_Sphere")) {
      return Optional.of(3857);
    }
    return Optional.empty();
  }

  // ------------------------------------------------------------------ generation

  /**
   * WKT1 for {@code epsg}, or null if the code is not in the table. {@code esri} selects ESRI
   * names.
   */
  static String wkt(int epsg, boolean esri) {
    Geographic g = GEOGRAPHIC.get(epsg);
    if (g != null) return geogcs(g, esri, true);
    int zone;
    Geographic base;
    boolean north = true;
    if (epsg >= 32601 && epsg <= 32660) {
      zone = epsg - 32600;
      base = GEOGRAPHIC.get(4326);
    } else if (epsg >= 32701 && epsg <= 32760) {
      zone = epsg - 32700;
      base = GEOGRAPHIC.get(4326);
      north = false;
    } else if (epsg >= 26901 && epsg <= 26923) {
      zone = epsg - 26900;
      base = GEOGRAPHIC.get(4269);
    } else if (epsg >= 25828 && epsg <= 25838) {
      zone = epsg - 25800;
      base = GEOGRAPHIC.get(4258);
    } else if (epsg == 27700) {
      return projcs(
          esri ? "OSGB_1936_British_National_Grid" : "OSGB 1936 / British National Grid",
          GEOGRAPHIC.get(4277),
          esri,
          "Transverse_Mercator",
          new double[][] {{49, -2, 0.9996012717, 400000, -100000}},
          epsg);
    } else if (epsg == 3857) {
      return esri
          ? "PROJCS[\"WGS_1984_Web_Mercator_Auxiliary_Sphere\","
              + geogcs(GEOGRAPHIC.get(4326), true, false)
              + ",PROJECTION[\"Mercator_Auxiliary_Sphere\"],PARAMETER[\"False_Easting\",0.0],PARAMETER[\"False_Northing\",0.0],"
              + "PARAMETER[\"Central_Meridian\",0.0],PARAMETER[\"Standard_Parallel_1\",0.0],"
              + "PARAMETER[\"Auxiliary_Sphere_Type\",0.0],UNIT[\"Meter\",1.0]]"
          : "PROJCS[\"WGS 84 / Pseudo-Mercator\","
              + geogcs(GEOGRAPHIC.get(4326), false, false)
              + ",PROJECTION[\"Mercator_1SP\"],PARAMETER[\"central_meridian\",0],PARAMETER[\"scale_factor\",1],"
              + "PARAMETER[\"false_easting\",0],PARAMETER[\"false_northing\",0],UNIT[\"metre\",1,AUTHORITY[\"EPSG\",\"9001\"]],"
              + "AUTHORITY[\"EPSG\",\"3857\"]]";
    } else {
      return null;
    }
    String label = base.epsg == 4326 ? "WGS 84" : base.epsg == 4269 ? "NAD83" : "ETRS89";
    String esriLabel =
        base.epsg == 4326 ? "WGS_1984" : base.epsg == 4269 ? "NAD_1983" : "ETRS_1989";
    String name =
        esri
            ? esriLabel + "_UTM_Zone_" + zone + (north ? "N" : "S")
            : label + " / UTM zone " + zone + (north ? "N" : "S");
    return projcs(
        name,
        base,
        esri,
        "Transverse_Mercator",
        new double[][] {{0, zone * 6 - 183, 0.9996, 500000, north ? 0 : 10000000}},
        epsg);
  }

  private static String num(double v) {
    return dev.jdal.geom.Wkt.number(v);
  }

  private static String esriNum(double v) {
    String s = num(v);
    return s.contains(".") || s.contains("E") ? s : s + ".0";
  }

  private static String geogcs(Geographic g, boolean esri, boolean withAuthority) {
    if (esri) {
      return "GEOGCS[\""
          + g.esriName
          + "\",DATUM[\""
          + g.esriDatum
          + "\",SPHEROID[\""
          + g.esriSpheroid
          + "\","
          + esriNum(g.a)
          + ","
          + g.invF
          + "]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]";
    }
    return "GEOGCS[\""
        + g.name
        + "\",DATUM[\""
        + g.datum
        + "\",SPHEROID[\""
        + g.spheroid
        + "\","
        + num(g.a)
        + ","
        + g.invF
        + ",AUTHORITY[\"EPSG\",\""
        + g.spheroidCode
        + "\"]],AUTHORITY[\"EPSG\",\""
        + g.datumCode
        + "\"]],"
        + "PRIMEM[\"Greenwich\",0,AUTHORITY[\"EPSG\",\"8901\"]],UNIT[\"degree\",0.0174532925199433,AUTHORITY[\"EPSG\",\"9122\"]]"
        + (withAuthority ? ",AUTHORITY[\"EPSG\",\"" + g.epsg + "\"]" : "")
        + "]";
  }

  private static String projcs(
      String name, Geographic g, boolean esri, String projection, double[][] p, int epsg) {
    double lat = p[0][0], lon = p[0][1], k = p[0][2], fe = p[0][3], fn = p[0][4];
    if (esri) {
      return "PROJCS[\""
          + name
          + "\","
          + geogcs(g, true, false)
          + ",PROJECTION[\""
          + projection
          + "\"],PARAMETER[\"False_Easting\","
          + esriNum(fe)
          + "],PARAMETER[\"False_Northing\","
          + esriNum(fn)
          + "],PARAMETER[\"Central_Meridian\","
          + esriNum(lon)
          + "],PARAMETER[\"Scale_Factor\","
          + esriNum(k)
          + "],PARAMETER[\"Latitude_Of_Origin\","
          + esriNum(lat)
          + "],UNIT[\"Meter\",1.0]]";
    }
    return "PROJCS[\""
        + name
        + "\","
        + geogcs(g, false, false)
        + ",PROJECTION[\""
        + projection
        + "\"],PARAMETER[\"latitude_of_origin\","
        + num(lat)
        + "],PARAMETER[\"central_meridian\","
        + num(lon)
        + "],PARAMETER[\"scale_factor\","
        + num(k)
        + "],PARAMETER[\"false_easting\","
        + num(fe)
        + "],PARAMETER[\"false_northing\","
        + num(fn)
        + "],UNIT[\"metre\",1,AUTHORITY[\"EPSG\",\"9001\"]],AUTHORITY[\"EPSG\",\""
        + epsg
        + "\"]]";
  }
}
