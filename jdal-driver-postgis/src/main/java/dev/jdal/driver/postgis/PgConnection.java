package dev.jdal.driver.postgis;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * A parsed PostgreSQL connection string: GDAL style {@code PG:host=... dbname=... user=...
 * password=...}, a {@code postgresql://user:password@host:port/db} URL, or a {@code
 * jdbc:postgresql:} URL.
 */
record PgConnection(String jdbcUrl, Properties properties, String schema) {

  static boolean isPgLocation(String s) {
    String l = s.stripLeading();
    return l.regionMatches(true, 0, "PG:", 0, 3)
        || l.startsWith("postgresql://")
        || l.startsWith("postgres://")
        || l.startsWith("jdbc:postgresql:");
  }

  static PgConnection parse(String location) {
    String l = location.strip();
    Properties props = new Properties();
    props.setProperty(
        "stringtype", "unspecified"); // lets text parameters be inferred as json, uuid, ...
    if (l.startsWith("jdbc:postgresql:")) return new PgConnection(l, props, "public");
    if (l.startsWith("postgresql://") || l.startsWith("postgres://")) {
      URI u = URI.create(l);
      String user = null, pw = null;
      if (u.getRawUserInfo() != null) {
        String[] up = u.getRawUserInfo().split(":", 2);
        user = URLDecoder.decode(up[0], StandardCharsets.UTF_8);
        if (up.length > 1) pw = URLDecoder.decode(up[1], StandardCharsets.UTF_8);
      }
      if (user != null) props.setProperty("user", user);
      if (pw != null) props.setProperty("password", pw);
      String url =
          "jdbc:postgresql://"
              + u.getHost()
              + (u.getPort() > 0 ? ":" + u.getPort() : "")
              + u.getRawPath()
              + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
      return new PgConnection(url, props, "public");
    }
    Map<String, String> kv = keyValues(l.substring(3));
    String host = kv.getOrDefault("host", "localhost");
    String port = kv.get("port");
    String db =
        kv.getOrDefault("dbname", kv.getOrDefault("database", kv.getOrDefault("user", "postgres")));
    if (kv.containsKey("user")) props.setProperty("user", kv.get("user"));
    if (kv.containsKey("password")) props.setProperty("password", kv.get("password"));
    String url = "jdbc:postgresql://" + host + (port != null ? ":" + port : "") + "/" + db;
    return new PgConnection(
        url, props, kv.getOrDefault("active_schema", kv.getOrDefault("schemas", "public")));
  }

  /** Parses {@code key=value key2='value with spaces'}. */
  static Map<String, String> keyValues(String s) {
    Map<String, String> out = new LinkedHashMap<>();
    int i = 0, n = s.length();
    while (i < n) {
      while (i < n && Character.isWhitespace(s.charAt(i))) i++;
      int eq = s.indexOf('=', i);
      if (eq < 0) break;
      String key = s.substring(i, eq).strip().toLowerCase(java.util.Locale.ROOT);
      i = eq + 1;
      StringBuilder v = new StringBuilder();
      if (i < n && s.charAt(i) == '\'') {
        i++;
        while (i < n && s.charAt(i) != '\'') {
          if (s.charAt(i) == '\\' && i + 1 < n) i++;
          v.append(s.charAt(i++));
        }
        i++;
      } else {
        while (i < n && !Character.isWhitespace(s.charAt(i))) v.append(s.charAt(i++));
      }
      out.put(key, v.toString());
    }
    return out;
  }
}
