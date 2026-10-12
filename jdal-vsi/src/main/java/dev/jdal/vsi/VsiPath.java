package dev.jdal.vsi;

/**
 * String operations on VSI paths ({@code /}-separated, any prefix), after CPLGetPath,
 * CPLGetBasename and friends.
 */
public final class VsiPath {

  private VsiPath() {}

  /** The directory part, or "" if there is none; {@code /a/b.txt} gives {@code /a}. */
  public static String parent(String path) {
    int i = lastSeparator(path);
    if (i < 0) return "";
    return i == 0 ? "/" : path.substring(0, i);
  }

  /** The last component. */
  public static String fileName(String path) {
    return path.substring(lastSeparator(path) + 1);
  }

  /** The file name without its last extension. */
  public static String baseName(String path) {
    String n = fileName(path);
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }

  /** The extension without the dot, or "". */
  public static String extension(String path) {
    String n = fileName(path);
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(dot + 1) : "";
  }

  public static String join(String dir, String name) {
    if (dir.isEmpty()) return name;
    return dir.endsWith("/") || dir.endsWith("\\") ? dir + name : dir + "/" + name;
  }

  /** A file in the same directory as {@code path}. */
  public static String sibling(String path, String name) {
    return join(parent(path), name);
  }

  /** Replaces the extension (or appends one). */
  public static String withExtension(String path, String ext) {
    String n = fileName(path);
    int dot = n.lastIndexOf('.');
    String stem = dot > 0 ? n.substring(0, dot) : n;
    return sibling(path, ext.isEmpty() ? stem : stem + "." + ext);
  }

  private static int lastSeparator(String path) {
    int end = path.length();
    while (end > 1 && (path.charAt(end - 1) == '/' || path.charAt(end - 1) == '\\')) end--;
    return Math.max(path.lastIndexOf('/', end - 1), path.lastIndexOf('\\', end - 1));
  }
}
