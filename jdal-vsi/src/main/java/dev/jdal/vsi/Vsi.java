package dev.jdal.vsi;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The virtual file system: routes a path to the {@link VsiHandler} that serves its prefix ({@code
 * /vsimem/}, {@code /vsigzip/}, ...) and to the local disk otherwise. {@link #global()} has the
 * handlers found on the class path; {@link #of} builds an isolated registry for tests.
 */
public final class Vsi {

  private static final class Holder {
    static final Vsi GLOBAL = load();

    private static Vsi load() {
      List<VsiHandler> found = new ArrayList<>();
      for (VsiHandler h : ServiceLoader.load(VsiHandler.class)) found.add(h);
      return new Vsi(found);
    }
  }

  private record Entry(String prefix, VsiHandler handler) {}

  private final VsiHandler local = new LocalHandler();
  private final List<Entry> entries = new ArrayList<>();

  private Vsi(List<VsiHandler> handlers) {
    for (VsiHandler h : handlers) {
      for (String prefix : h.prefixes()) {
        for (Entry e : entries) {
          if (e.prefix.equals(prefix)) {
            throw new IllegalStateException(
                "Two VSI handlers claim " + prefix + ": " + e.handler + " and " + h);
          }
        }
        entries.add(new Entry(prefix, h));
      }
    }
    entries.sort(Comparator.comparingInt((Entry e) -> e.prefix.length()).reversed());
  }

  /** The registry with the handlers on the class path. */
  public static Vsi global() {
    return Holder.GLOBAL;
  }

  /** A registry with only the local disk and the given handlers. */
  public static Vsi of(VsiHandler... handlers) {
    return new Vsi(List.of(handlers));
  }

  // ------------------------------------------------------------------ routing

  /** Maps {@code file:} URIs to plain paths. */
  private static String normalize(String path) {
    if (path.startsWith("file:")) {
      try {
        return java.nio.file.Path.of(java.net.URI.create(path)).toString();
      } catch (RuntimeException e) {
        return path;
      }
    }
    return path;
  }

  /** The handler for {@code path}: the longest matching prefix, else the local disk. */
  public VsiHandler handler(String path) {
    for (Entry e : entries) {
      String p = e.prefix;
      if (path.startsWith(p)) return e.handler;
      // "/vsimem" is "/vsimem/", and "/vsimem\foo" is "/vsimem/foo"
      if (path.length() + 1 == p.length() && p.startsWith(path)) return e.handler;
      if (path.length() >= p.length()
          && path.charAt(p.length() - 1) == '\\'
          && path.startsWith(p.substring(0, p.length() - 1))) {
        return e.handler;
      }
    }
    return local;
  }

  /** True if the path is on the local disk (no virtual prefix). */
  public boolean isLocal(String path) {
    return handler(normalize(path)).isLocal();
  }

  // ------------------------------------------------------------------ operations

  public VsiFile open(String path, VsiMode mode) throws IOException {
    return open(path, mode, Map.of());
  }

  public VsiFile open(String path, VsiMode mode, Map<String, String> options) throws IOException {
    String p = normalize(path);
    return handler(p).open(this, p, mode, options);
  }

  public Optional<VsiStat> stat(String path) {
    String p = normalize(path);
    try {
      return handler(p).stat(this, p);
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  public boolean exists(String path) {
    return stat(path).isPresent();
  }

  public boolean isFile(String path) {
    return stat(path).filter(VsiStat::isFile).isPresent();
  }

  public boolean isDirectory(String path) {
    return stat(path).filter(VsiStat::directory).isPresent();
  }

  /** The names of the entries of a directory, sorted. */
  public List<String> list(String dir) throws IOException {
    String p = normalize(dir);
    return handler(p).list(this, p);
  }

  /**
   * All entries below {@code dir}, depth first, as paths relative to it; directories end with a
   * slash (GDAL's {@code VSIReadDirRecursive}).
   */
  public List<String> listRecursive(String dir) throws IOException {
    List<String> out = new ArrayList<>();
    walk(dir, "", out);
    return out;
  }

  private void walk(String dir, String prefix, List<String> out) throws IOException {
    for (String name : list(dir)) {
      String full = VsiPath.join(dir, name);
      if (isDirectory(full)) {
        out.add(prefix + name + "/");
        walk(full, prefix + name + "/", out);
      } else {
        out.add(prefix + name);
      }
    }
  }

  /** The names of the files next to {@code path}, or null if the handler cannot tell. */
  public List<String> siblings(String path) throws IOException {
    String p = normalize(path);
    return handler(p).siblings(this, p);
  }

  public void delete(String path) throws IOException {
    String p = normalize(path);
    handler(p).delete(this, p);
  }

  /** Deletes a file if it exists; returns whether it did. */
  public boolean deleteIfExists(String path) throws IOException {
    try {
      delete(path);
      return true;
    } catch (NoSuchFileException e) {
      return false;
    }
  }

  /** Deletes a file, or a directory with everything in it; fails if the path does not exist. */
  public void deleteRecursive(String path) throws IOException {
    if (!exists(path)) throw new NoSuchFileException(path);
    if (isDirectory(path)) {
      for (String name : list(path)) deleteRecursive(VsiPath.join(path, name));
    }
    delete(path);
  }

  public void mkdir(String dir) throws IOException {
    String p = normalize(dir);
    handler(p).mkdir(this, p);
  }

  /** Creates a directory and the missing parents. */
  public void mkdirs(String dir) throws IOException {
    if (dir.isEmpty() || isDirectory(dir)) return;
    String parent = VsiPath.parent(dir);
    if (!parent.isEmpty() && !parent.equals(dir)) mkdirs(parent);
    if (!isDirectory(dir)) mkdir(dir);
  }

  /** Renames, replacing the target; across handlers it copies and deletes. */
  public void rename(String from, String to) throws IOException {
    String a = normalize(from);
    String b = normalize(to);
    VsiHandler ha = handler(a);
    if (ha == handler(b)) {
      ha.rename(this, a, b);
    } else {
      writeBytes(b, readAllBytes(a));
      delete(a);
    }
  }

  // ------------------------------------------------------------------ whole-file helpers

  public byte[] readAllBytes(String path) throws IOException {
    try (VsiFile f = open(path, VsiMode.READ)) {
      long size = f.size();
      if (size > Integer.MAX_VALUE - 8) throw new IOException("File too large: " + path);
      ByteBuffer buf = ByteBuffer.allocate((int) size);
      while (buf.hasRemaining() && f.read(buf) >= 0) {
        // keep reading
      }
      return buf.array();
    }
  }

  public void writeBytes(String path, byte[] data) throws IOException {
    try (VsiFile f = open(path, VsiMode.WRITE)) {
      ByteBuffer b = ByteBuffer.wrap(data);
      while (b.hasRemaining()) f.write(b);
    }
  }

  public String readString(String path, Charset cs) throws IOException {
    return new String(readAllBytes(path), cs);
  }

  public String readString(String path) throws IOException {
    return readString(path, StandardCharsets.UTF_8);
  }

  public void writeString(String path, String text) throws IOException {
    writeBytes(path, text.getBytes(StandardCharsets.UTF_8));
  }

  public InputStream newInputStream(String path) throws IOException {
    return Channels.newInputStream(open(path, VsiMode.READ));
  }

  public OutputStream newOutputStream(String path, VsiMode mode) throws IOException {
    return Channels.newOutputStream(open(path, mode));
  }

  public BufferedReader newReader(String path, Charset cs) throws IOException {
    return new BufferedReader(new InputStreamReader(newInputStream(path), cs));
  }

  public BufferedReader newReader(String path) throws IOException {
    return newReader(path, StandardCharsets.UTF_8);
  }

  public BufferedWriter newWriter(String path, VsiMode mode, Charset cs) throws IOException {
    return new BufferedWriter(new OutputStreamWriter(newOutputStream(path, mode), cs));
  }

  public BufferedWriter newWriter(String path) throws IOException {
    return newWriter(path, VsiMode.WRITE, StandardCharsets.UTF_8);
  }
}
