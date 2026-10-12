package dev.jdal.vsi;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * {@code /vsizip/}: files inside zip archives. The archive is found in the path as GDAL does it:
 * {@code /vsizip/{archive}/member} with balanced curly braces, or by the extension of the archive
 * ({@code .zip}, {@code .kmz}, ...). The archive itself may be virtual, also another zip.
 *
 * <p>Writing: opening the archive in write mode creates it; opening a member in write mode adds the
 * entry, to a new archive or appended to an existing one. One entry is written at a time. An
 * archive being written cannot be read.
 */
public final class ZipHandler implements VsiHandler {

  private static final String PREFIX = "/vsizip/";
  private static final List<String> EXTENSIONS =
      List.of(".zip", ".kmz", ".dwf", ".ods", ".xlsx", ".xlsm");
  private static final int MAX_NESTED_SEARCH = 5;

  private record Split(String archive, String member) {}

  private record Cached(long size, long modified, ZipIndex index) {}

  private static final class Writing {
    final ZipWriter writer;
    boolean archiveHandleOpen;
    boolean closeWithEntry;

    Writing(ZipWriter writer) {
      this.writer = writer;
    }
  }

  private final Map<String, Cached> indexes = new ConcurrentHashMap<>();
  private final Map<String, Writing> writing = new HashMap<>();

  @Override
  public List<String> prefixes() {
    return List.of(PREFIX);
  }

  // ------------------------------------------------------------------ path handling

  private static String compact(String member) {
    String m = member.replace('\\', '/');
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < m.length(); i++) {
      char c = m.charAt(i);
      if (c == '/' && i > 0 && m.charAt(i - 1) == '/') continue;
      sb.append(c);
    }
    String r = sb.toString();
    while (r.startsWith("/")) r = r.substring(1);
    while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
    return r;
  }

  /** The text after the prefix: the archive path (possibly braced) and the member. */
  private static String rest(String path) {
    String r =
        path.startsWith(PREFIX)
            ? path.substring(PREFIX.length() - 1)
            : path.substring(PREFIX.length() - 2);
    return r; // starts with '/'
  }

  /** {@code {archive}} syntax; null if the braces are unbalanced. */
  private static Split bracedSplit(String rest) {
    int depth = 0;
    int i = 1; // rest is "/{...}"
    for (; i < rest.length(); i++) {
      char c = rest.charAt(i);
      if (c == '{') depth++;
      else if (c == '}' && --depth == 0) break;
    }
    if (depth != 0 || i >= rest.length()) return null;
    String archive = rest.substring(2, i);
    String after = rest.substring(i + 1);
    if (after.isEmpty()) return new Split(archive, "");
    if (after.charAt(0) != '/' && after.charAt(0) != '\\') return null;
    return new Split(archive, compact(after));
  }

  /** Finds the archive in a path; with {@code mustExist} the archive file has to be there. */
  private Split split(Vsi vsi, String path, boolean mustExist) {
    String rest = rest(path); // "/..."
    if (rest.length() < 2) return null;
    if (rest.charAt(1) == '{') {
      Split s = bracedSplit(rest);
      if (s == null) return null;
      return !mustExist || vsi.isFile(s.archive()) ? s : null;
    }
    // natural chaining: /vsizip/vsimem/x.zip is /vsizip//vsimem/x.zip
    String s = rest.startsWith("/vsi") ? rest : rest.substring(1);
    int attempts = 0;
    for (int i = 0; i < s.length(); i++) {
      for (String ext : EXTENSIONS) {
        if (!s.regionMatches(true, i, ext, 0, ext.length())) continue;
        int endExt = i + ext.length();
        if (endExt < s.length() && s.charAt(endExt) != '/' && s.charAt(endExt) != '\\') continue;
        String archive = s.substring(0, endExt);
        if (!mustExist || vsi.isFile(archive)) {
          return new Split(archive, endExt < s.length() ? compact(s.substring(endExt)) : "");
        }
        if (++attempts == MAX_NESTED_SEARCH) return null;
      }
    }
    return null;
  }

  private Split requireSplit(Vsi vsi, String path, boolean mustExist) throws IOException {
    Split s = split(vsi, path, mustExist);
    if (s == null) throw new NoSuchFileException(path);
    return s;
  }

  // ------------------------------------------------------------------ reading

  private synchronized void checkNotWritten(String archive) throws IOException {
    Writing w = writing.get(archive);
    if (w != null) throw new IOException("Cannot read a zip file being written");
  }

  private ZipIndex index(Vsi vsi, String archive) throws IOException {
    VsiStat st = vsi.stat(archive).orElseThrow(() -> new NoSuchFileException(archive));
    Cached c = indexes.get(archive);
    if (c != null && c.size() == st.size() && c.modified() == st.modified()) return c.index();
    try (VsiFile f = vsi.open(archive, VsiMode.READ)) {
      ZipIndex idx = ZipIndex.read(f);
      indexes.put(archive, new Cached(st.size(), st.modified(), idx));
      return idx;
    }
  }

  @Override
  public VsiFile open(Vsi vsi, String path, VsiMode mode, Map<String, String> options)
      throws IOException {
    if (mode.writable()) return openForWrite(vsi, path, mode);
    Split s = requireSplit(vsi, path, true);
    checkNotWritten(s.archive());
    ZipIndex idx = index(vsi, s.archive());
    ZipIndex.Entry e;
    if (s.member().isEmpty()) {
      List<ZipIndex.Entry> files = idx.entries.stream().filter(x -> !x.directory()).toList();
      if (files.size() != 1) {
        throw new IOException(
            "Support only 1 file in archive file "
                + s.archive()
                + " when no explicit in-archive filename is specified");
      }
      e = files.getFirst();
    } else {
      e = idx.find(s.member());
      if (e == null) {
        if (idx.find(s.member() + "/") != null || isImplicitDirectory(idx, s.member())) {
          throw new IOException("Is a directory: " + path);
        }
        throw new NoSuchFileException(path);
      }
    }
    return openEntry(vsi, s.archive(), e);
  }

  private static boolean isImplicitDirectory(ZipIndex idx, String member) {
    String prefix = member + "/";
    return idx.entries.stream().anyMatch(x -> x.name().startsWith(prefix));
  }

  private static VsiFile openEntry(Vsi vsi, String archive, ZipIndex.Entry e) throws IOException {
    if ((e.flags() & 1) != 0)
      throw new IOException("Encrypted zip entries are not supported: " + e.name());
    VsiFile f = vsi.open(archive, VsiMode.READ);
    try {
      ByteBuffer h = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
      ZipIndex.readFully(f, h, e.localOffset());
      if (h.getInt(0) != ZipIndex.LOC_SIG)
        throw new IOException("Corrupt zip file: bad local header for " + e.name());
      long dataStart = e.localOffset() + 30 + (h.getShort(26) & 0xFFFF) + (h.getShort(28) & 0xFFFF);
      if (e.size() == 0) return new InflatingFile(InputStream::nullInputStream, () -> 0, f);
      if (e.method() == 0) return new RangeFile(f, dataStart, e.size(), false);
      if (e.method() != 8)
        throw new IOException("Unsupported zip compression method " + e.method());
      return new InflatingFile(
          () ->
              new InflaterInputStream(
                  new BufferedInputStream(
                      new PositionalInputStream(f, dataStart, e.compressedSize()), 64 * 1024),
                  new Inflater(true),
                  64 * 1024),
          e::size,
          f);
    } catch (IOException | RuntimeException ex) {
      f.close();
      throw ex;
    }
  }

  @Override
  public Optional<VsiStat> stat(Vsi vsi, String path) throws IOException {
    Split s = split(vsi, path, true);
    if (s == null) return Optional.empty();
    synchronized (this) {
      if (writing.containsKey(s.archive())) return Optional.empty();
    }
    ZipIndex idx;
    try {
      idx = index(vsi, s.archive());
    } catch (IOException e) {
      return Optional.empty();
    }
    if (s.member().isEmpty()) return Optional.of(VsiStat.dir());
    ZipIndex.Entry f = idx.find(s.member());
    if (f != null) return Optional.of(VsiStat.file(f.size(), f.modified()));
    ZipIndex.Entry d = idx.find(s.member() + "/");
    if (d != null) return Optional.of(new VsiStat(true, 0, d.modified()));
    return isImplicitDirectory(idx, s.member()) ? Optional.of(VsiStat.dir()) : Optional.empty();
  }

  @Override
  public List<String> list(Vsi vsi, String dir) throws IOException {
    Split s = requireSplit(vsi, dir, true);
    checkNotWritten(s.archive());
    ZipIndex idx = index(vsi, s.archive());
    String prefix = s.member().isEmpty() ? "" : s.member() + "/";
    LinkedHashSet<String> names = new LinkedHashSet<>();
    boolean found = s.member().isEmpty();
    for (ZipIndex.Entry e : idx.entries) {
      if (!e.name().startsWith(prefix)) continue;
      found = true;
      String rest = e.name().substring(prefix.length());
      if (rest.isEmpty()) continue;
      int slash = rest.indexOf('/');
      names.add(slash < 0 ? rest : rest.substring(0, slash));
    }
    if (!found) throw new NoSuchFileException(dir);
    return new ArrayList<>(names);
  }

  // ------------------------------------------------------------------ writing

  private synchronized VsiFile openForWrite(Vsi vsi, String path, VsiMode mode) throws IOException {
    if (mode != VsiMode.WRITE && mode != VsiMode.APPEND) {
      throw new IOException("Only plain write access is supported for " + PREFIX);
    }
    Split s = requireSplit(vsi, path, false);
    String archive = s.archive();
    Writing w = writing.get(archive);
    if (s.member().isEmpty()) {
      if (w != null) throw new IOException("The zip file " + archive + " is already being written");
      Writing nw = new Writing(ZipWriter.create(vsi, archive));
      nw.archiveHandleOpen = true;
      writing.put(archive, nw);
      indexes.remove(archive);
      return new ArchiveHandle(archive, nw);
    }
    String name = s.member() + (path.endsWith("/") || path.endsWith("\\") ? "/" : "");
    if (w == null) {
      w =
          new Writing(
              vsi.exists(archive)
                  ? ZipWriter.append(vsi, archive)
                  : ZipWriter.create(vsi, archive));
      w.closeWithEntry = true;
      writing.put(archive, w);
      indexes.remove(archive);
    }
    try {
      VsiFile entry = w.writer.addEntry(name);
      Writing owner = w;
      return new EntryHandle(entry, () -> entryClosed(archive, owner));
    } catch (IOException | RuntimeException e) {
      if (w.closeWithEntry && !w.writer.entryOpen()) closeWriting(archive, w);
      throw e;
    }
  }

  private synchronized void entryClosed(String archive, Writing w) throws IOException {
    if (w.closeWithEntry) closeWriting(archive, w);
  }

  private synchronized void closeWriting(String archive, Writing w) throws IOException {
    writing.remove(archive);
    indexes.remove(archive);
    w.writer.finish();
  }

  @Override
  public void mkdir(Vsi vsi, String dir) throws IOException {
    openForWrite(vsi, dir.endsWith("/") ? dir : dir + "/", VsiMode.WRITE).close();
  }

  /** The handle for the archive itself while it is being written. */
  private final class ArchiveHandle extends ReadOnlyVsiFile {
    private final String archive;
    private final Writing w;

    ArchiveHandle(String archive, Writing w) {
      this.archive = archive;
      this.w = w;
    }

    @Override
    protected int readAt(ByteBuffer dst, long pos) throws IOException {
      throw new IOException("Cannot read a zip file being written");
    }

    @Override
    public long size() {
      return 0;
    }

    @Override
    protected void closeResources() throws IOException {
      synchronized (ZipHandler.this) {
        w.archiveHandleOpen = false;
        w.closeWithEntry = true;
        if (!w.writer.entryOpen()) closeWriting(archive, w);
      }
    }
  }

  /** The handle of one entry being written; tells the handler when it is done. */
  private static final class EntryHandle implements VsiFile {
    interface Done {
      void run() throws IOException;
    }

    private final VsiFile entry;
    private final Done done;

    EntryHandle(VsiFile entry, Done done) {
      this.entry = entry;
      this.done = done;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
      return entry.read(dst);
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
      return entry.read(dst, position);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
      return entry.write(src);
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
      return entry.write(src, position);
    }

    @Override
    public long position() throws IOException {
      return entry.position();
    }

    @Override
    public VsiFile position(long newPosition) throws IOException {
      entry.position(newPosition);
      return this;
    }

    @Override
    public long size() throws IOException {
      return entry.size();
    }

    @Override
    public VsiFile truncate(long size) throws IOException {
      entry.truncate(size);
      return this;
    }

    @Override
    public void setLength(long length) throws IOException {
      entry.setLength(length);
    }

    @Override
    public boolean isOpen() {
      return entry.isOpen();
    }

    @Override
    public void close() throws IOException {
      if (!entry.isOpen()) return;
      try {
        entry.close();
      } finally {
        done.run();
      }
    }
  }
}
