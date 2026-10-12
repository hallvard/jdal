package dev.jdal.config;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Configuration options, such as GDAL's {@code GDAL_HTTP_*}. A value is looked up in this order:
 * Java system properties, environment variables, and the {@code .env} file in the current directory
 * (read once). The file is in {@link Properties} format ({@code KEY=value}, {@code #} comments;
 * backslashes must be doubled); an {@code export } prefix and one pair of quotes around the value
 * are removed, as in the usual {@code .env} files.
 */
public final class Config {

  private static final class Holder {
    static final Config GLOBAL = of(Path.of(System.getProperty("user.dir", ".")), System::getenv);
  }

  private final Properties file;
  private final Function<String, String> environment;

  private Config(Properties file, Function<String, String> environment) {
    this.file = file;
    this.environment = environment;
  }

  /** The configuration of this process, with the {@code .env} file of the current directory. */
  public static Config global() {
    return Holder.GLOBAL;
  }

  /**
   * A configuration with the {@code .env} file of {@code directory} and the given environment (for
   * tests).
   */
  public static Config of(Path directory, Function<String, String> environment) {
    return new Config(read(directory.resolve(".env")), environment);
  }

  private static Properties read(Path envFile) {
    Properties p = new Properties();
    if (!Files.isRegularFile(envFile)) return p;
    try {
      StringBuilder text = new StringBuilder();
      for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
        String l = line.stripLeading();
        text.append(l.startsWith("export ") ? l.substring(7).stripLeading() : line).append('\n');
      }
      try (Reader r = new StringReader(text.toString())) {
        p.load(r);
      }
    } catch (IOException | IllegalArgumentException e) {
      // an unreadable or malformed file is the same as no file
      return new Properties();
    }
    for (Map.Entry<Object, Object> e : p.entrySet())
      e.setValue(unquote(((String) e.getValue()).strip()));
    return p;
  }

  private static String unquote(String v) {
    if (v.length() >= 2
        && (v.charAt(0) == '"' || v.charAt(0) == '\'')
        && v.charAt(v.length() - 1) == v.charAt(0)) {
      return v.substring(1, v.length() - 1);
    }
    return v;
  }

  /** The value of an option, or null. */
  public String get(String key) {
    String v = System.getProperty(key);
    if (v != null) return v;
    v = environment.apply(key);
    return v != null ? v : file.getProperty(key);
  }

  public String get(String key, String fallback) {
    String v = get(key);
    return v != null ? v : fallback;
  }
}
