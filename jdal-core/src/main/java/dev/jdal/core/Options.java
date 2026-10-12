package dev.jdal.core;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Driver options (open, dataset creation or layer creation options) with case-insensitive keys. */
public final class Options {

  private final Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

  private Options(Map<String, String> source) {
    map.putAll(source);
  }

  public static Options of(Map<String, String> source) {
    return new Options(source);
  }

  public boolean has(String key) {
    return map.containsKey(key);
  }

  public String get(String key, String defaultValue) {
    return map.getOrDefault(key, defaultValue);
  }

  public String get(String key) {
    return map.get(key);
  }

  public boolean bool(String key, boolean defaultValue) {
    String v = map.get(key);
    if (v == null) return defaultValue;
    return switch (v.toUpperCase(Locale.ROOT)) {
      case "YES", "TRUE", "ON", "1" -> true;
      case "NO", "FALSE", "OFF", "0" -> false;
      default -> throw new JdalException("Option " + key + " must be YES or NO, not '" + v + "'");
    };
  }

  public int integer(String key, int defaultValue) {
    String v = map.get(key);
    if (v == null) return defaultValue;
    try {
      return Integer.parseInt(v.strip());
    } catch (NumberFormatException e) {
      throw new JdalException("Option " + key + " must be an integer, not '" + v + "'");
    }
  }

  /** The upper-cased value, which must be one of {@code allowed} (upper case). */
  public String choice(String key, String defaultValue, String... allowed) {
    String v = map.getOrDefault(key, defaultValue).toUpperCase(Locale.ROOT);
    for (String a : allowed) if (a.equals(v)) return v;
    throw new JdalException("Option " + key + " has invalid value '" + v + "'");
  }
}
