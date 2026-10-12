package dev.jdal.core;

import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiPath;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

/**
 * What a driver sees when asked to {@linkplain Driver#identify identify} a location: the location
 * text and, lazily, the first bytes of the file.
 */
public final class OpenInfo {

  public static final int HEADER_SIZE = 4096;

  private final String location;
  private final boolean pathLike;
  private final Map<String, String> options;
  private final Vsi vsi = Vsi.global();
  private byte[] header;

  public OpenInfo(String location, Map<String, String> options) {
    this.location = location;
    this.options = options;
    this.pathLike = isPath(location);
  }

  /** False for inline text and connection strings such as {@code PG:dbname=x}. */
  private static boolean isPath(String location) {
    String s = location.stripLeading();
    if (s.startsWith("{")) return false; // inline text
    if (s.startsWith("/vsi") || s.startsWith("file:")) return true;
    return !s.contains(":") || s.matches("^[A-Za-z]:[\\\\/].*");
  }

  public String location() {
    return location;
  }

  /**
   * True if the location names a file or directory (local or virtual), not a connection string or
   * text.
   */
  public boolean isPath() {
    return pathLike;
  }

  public boolean isFile() {
    return pathLike && vsi.isFile(location);
  }

  public boolean isDirectory() {
    return pathLike && vsi.isDirectory(location);
  }

  /** Lower-case extension without dot, or "". */
  public String extension() {
    String n = pathLike ? VsiPath.fileName(location) : location;
    int i = n.lastIndexOf('.');
    return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.ROOT);
  }

  public Map<String, String> options() {
    return options;
  }

  /** Up to {@value #HEADER_SIZE} bytes from the start of the file; empty for non-files. */
  public synchronized byte[] header() {
    if (header == null) {
      header = new byte[0];
      if (isFile()) {
        try (InputStream in = vsi.newInputStream(location)) {
          header = in.readNBytes(HEADER_SIZE);
        } catch (IOException e) {
          // unreadable file: no header, drivers will fail on open with a proper error
        }
      }
    }
    return header;
  }

  public boolean headerStartsWith(String prefix) {
    byte[] h = header();
    byte[] p = prefix.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    if (h.length < p.length) return false;
    for (int i = 0; i < p.length; i++) if (h[i] != p[i]) return false;
    return true;
  }

  public boolean headerContains(String text) {
    return new String(header(), java.nio.charset.StandardCharsets.ISO_8859_1).contains(text);
  }
}
