package dev.jdal.vsi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@code /vsimem/}: files held in memory, shared by everything in the process. A file that is
 * deleted while open stays usable through the open handles, like in GDAL.
 */
public final class MemHandler implements VsiHandler {

  private static final String PREFIX = "/vsimem/";

  private final ConcurrentMap<String, MemFile> files = new ConcurrentHashMap<>();
  private final java.util.Set<String> dirs = ConcurrentHashMap.newKeySet();

  @Override
  public List<String> prefixes() {
    return List.of(PREFIX);
  }

  /** Canonical form: {@code /vsimem/a/b}, no trailing slash, no doubled or backward slashes. */
  static String normalize(String path) {
    String p = path.replace('\\', '/');
    StringBuilder sb = new StringBuilder(p.length());
    for (int i = 0; i < p.length(); i++) {
      char c = p.charAt(i);
      if (c == '/' && i > 0 && p.charAt(i - 1) == '/') continue;
      sb.append(c);
    }
    String r = sb.toString();
    while (r.length() > PREFIX.length() && r.endsWith("/")) r = r.substring(0, r.length() - 1);
    return r.equals("/vsimem") ? PREFIX : r;
  }

  @Override
  public VsiFile open(Vsi vsi, String path, VsiMode mode, Map<String, String> options)
      throws IOException {
    String p = normalize(path);
    if (isDirectory(p)) throw new IOException("Is a directory: " + path);
    if (mode.writable()) {
      for (int i = p.indexOf('/', PREFIX.length()); i > 0; i = p.indexOf('/', i + 1)) {
        if (files.containsKey(p.substring(0, i))) throw new IOException("Not a directory: " + path);
      }
    }
    MemFile f;
    switch (mode) {
      case READ, UPDATE -> {
        f = files.get(p);
        if (f == null) throw new NoSuchFileException(path);
      }
      case WRITE, WRITE_UPDATE -> {
        f = files.computeIfAbsent(p, k -> new MemFile());
        f.truncate(0);
      }
      default -> f = files.computeIfAbsent(p, k -> new MemFile());
    }
    return new MemChannel(f, mode);
  }

  private boolean isDirectory(String p) {
    if (p.equals(PREFIX) || dirs.contains(p)) return true;
    String prefix = p + "/";
    for (String k : files.keySet()) if (k.startsWith(prefix)) return true;
    for (String d : dirs) if (d.startsWith(prefix)) return true;
    return false;
  }

  @Override
  public Optional<VsiStat> stat(Vsi vsi, String path) {
    String p = normalize(path);
    MemFile f = files.get(p);
    if (f != null) return Optional.of(VsiStat.file(f.size(), f.modified));
    return isDirectory(p) ? Optional.of(VsiStat.dir()) : Optional.empty();
  }

  @Override
  public List<String> list(Vsi vsi, String dir) throws IOException {
    String p = normalize(dir);
    if (!isDirectory(p)) throw new NoSuchFileException(dir);
    String prefix = p.endsWith("/") ? p : p + "/";
    TreeSet<String> names = new TreeSet<>();
    for (String k : files.keySet()) addChild(names, prefix, k);
    for (String d : dirs) addChild(names, prefix, d);
    return List.copyOf(names);
  }

  private static void addChild(TreeSet<String> names, String prefix, String key) {
    if (!key.startsWith(prefix) || key.length() == prefix.length()) return;
    int slash = key.indexOf('/', prefix.length());
    names.add(slash < 0 ? key.substring(prefix.length()) : key.substring(prefix.length(), slash));
  }

  @Override
  public void delete(Vsi vsi, String path) throws IOException {
    String p = normalize(path);
    if (files.remove(p) != null) return;
    if (!isDirectory(p)) throw new NoSuchFileException(path);
    if (!list(vsi, p).isEmpty()) throw new DirectoryNotEmptyException(path);
    dirs.remove(p);
  }

  @Override
  public void mkdir(Vsi vsi, String dir) throws IOException {
    String p = normalize(dir);
    if (files.containsKey(p) || isDirectory(p)) throw new FileAlreadyExistsException(dir);
    dirs.add(p);
  }

  @Override
  public void rename(Vsi vsi, String from, String to) throws IOException {
    String a = normalize(from);
    String b = normalize(to);
    MemFile f = files.remove(a);
    if (f != null) {
      files.put(b, f);
      return;
    }
    if (!isDirectory(a)) throw new NoSuchFileException(from);
    String prefix = a + "/";
    for (String k : List.copyOf(files.keySet())) {
      if (k.startsWith(prefix)) files.put(b + "/" + k.substring(prefix.length()), files.remove(k));
    }
    for (String d : List.copyOf(dirs)) {
      if (d.equals(a) || d.startsWith(prefix)) {
        dirs.remove(d);
        dirs.add(b + d.substring(a.length()));
      }
    }
    dirs.add(b);
  }

  @Override
  public List<String> siblings(Vsi vsi, String path) {
    try {
      return list(vsi, VsiPath.parent(normalize(path)));
    } catch (IOException e) {
      return List.of();
    }
  }

  /** Removes everything; for tests. */
  public void clear() {
    files.clear();
    dirs.clear();
  }

  /** The bytes of a file and its length; guarded by {@code this}. */
  static final class MemFile {
    private byte[] data = new byte[0];
    private int size;
    volatile long modified = System.currentTimeMillis();

    synchronized long size() {
      return size;
    }

    synchronized int read(ByteBuffer dst, long position) {
      if (position >= size) return -1;
      int n = (int) Math.min(dst.remaining(), size - position);
      dst.put(data, (int) position, n);
      return n;
    }

    synchronized int write(ByteBuffer src, long position) throws IOException {
      int n = src.remaining();
      long end = position + n;
      if (position > Integer.MAX_VALUE - 8 || end > Integer.MAX_VALUE - 8)
        throw new IOException("/vsimem/ file too large");
      if (end > data.length) data = Arrays.copyOf(data, (int) Math.max(end, data.length * 2L));
      if (position > size) Arrays.fill(data, size, (int) position, (byte) 0);
      src.get(data, (int) position, n);
      size = (int) Math.max(size, end);
      modified = System.currentTimeMillis();
      return n;
    }

    synchronized void truncate(long newSize) {
      if (newSize < size) size = (int) newSize;
      else if (newSize > size) {
        if (newSize > data.length) data = Arrays.copyOf(data, (int) newSize);
        Arrays.fill(data, size, (int) newSize, (byte) 0);
        size = (int) newSize;
      }
      modified = System.currentTimeMillis();
    }
  }

  private static final class MemChannel implements VsiFile {
    private final MemFile file;
    private final VsiMode mode;
    private long position;
    private boolean open = true;

    MemChannel(MemFile file, VsiMode mode) {
      this.file = file;
      this.mode = mode;
      if (mode == VsiMode.APPEND) position = file.size();
    }

    private void check(boolean needRead, boolean needWrite) throws IOException {
      if (!open) throw new java.nio.channels.ClosedChannelException();
      if (needRead && !mode.readable()) throw new java.nio.channels.NonReadableChannelException();
      if (needWrite && !mode.writable()) throw new java.nio.channels.NonWritableChannelException();
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
      check(true, false);
      int n = file.read(dst, position);
      if (n > 0) position += n;
      return n;
    }

    @Override
    public int read(ByteBuffer dst, long pos) throws IOException {
      check(true, false);
      return file.read(dst, pos);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
      check(false, true);
      if (mode == VsiMode.APPEND) position = file.size();
      int n = file.write(src, position);
      position += n;
      return n;
    }

    @Override
    public int write(ByteBuffer src, long pos) throws IOException {
      check(false, true);
      return file.write(src, pos);
    }

    @Override
    public long position() throws IOException {
      check(false, false);
      return position;
    }

    @Override
    public MemChannel position(long newPosition) throws IOException {
      check(false, false);
      if (newPosition < 0) throw new IllegalArgumentException("negative position");
      position = newPosition;
      return this;
    }

    @Override
    public long size() throws IOException {
      check(false, false);
      return file.size();
    }

    @Override
    public MemChannel truncate(long size) throws IOException {
      check(false, true);
      file.truncate(Math.min(size, file.size()));
      if (position > size) position = size;
      return this;
    }

    @Override
    public void setLength(long length) throws IOException {
      check(false, true);
      file.truncate(length);
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
    }
  }
}
