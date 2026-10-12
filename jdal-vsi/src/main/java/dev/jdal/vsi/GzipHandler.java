package dev.jdal.vsi;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * {@code /vsigzip/path}: reads gzip files (plain files pass through unchanged) and writes them. The
 * path after the prefix is opened through {@link Vsi}, so it can itself be virtual.
 */
public final class GzipHandler implements VsiHandler {

  private static final String PREFIX = "/vsigzip/";

  @Override
  public List<String> prefixes() {
    return List.of(PREFIX);
  }

  private static String inner(String path) {
    String p =
        path.startsWith(PREFIX)
            ? path.substring(PREFIX.length())
            : path.substring(PREFIX.length() - 1);
    return p;
  }

  @Override
  public VsiFile open(Vsi vsi, String path, VsiMode mode, Map<String, String> options)
      throws IOException {
    String target = inner(path);
    if (mode == VsiMode.WRITE || mode == VsiMode.APPEND) {
      return new GzipWriter(vsi.open(target, VsiMode.WRITE));
    }
    if (mode != VsiMode.READ)
      throw new IOException("Only read-only or write-only access to " + PREFIX);
    VsiFile f = vsi.open(target, VsiMode.READ);
    try {
      if (!isGzip(f)) return f; // not compressed: behave like the plain file
      return new InflatingFile(
          () ->
              new GZIPInputStream(
                  new BufferedInputStream(new PositionalInputStream(f, 0, f.size()), 64 * 1024)),
          () -> uncompressedSize(f),
          f);
    } catch (IOException | RuntimeException e) {
      f.close();
      throw e;
    }
  }

  private static boolean isGzip(VsiFile f) throws IOException {
    ByteBuffer b = ByteBuffer.allocate(2);
    return f.read(b, 0) == 2 && (b.get(0) & 0xFF) == 0x1F && (b.get(1) & 0xFF) == 0x8B;
  }

  /** The ISIZE field in the trailer: the uncompressed length modulo 2^32. */
  private static long uncompressedSize(VsiFile f) {
    try {
      long size = f.size();
      if (size < 18) return 0;
      ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
      f.read(b, size - 4);
      return b.getInt(0) & 0xFFFFFFFFL;
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Override
  public Optional<VsiStat> stat(Vsi vsi, String path) throws IOException {
    String target = inner(path);
    Optional<VsiStat> st = vsi.stat(target);
    if (st.isEmpty() || st.get().directory()) return st;
    try (VsiFile f = vsi.open(target, VsiMode.READ)) {
      return Optional.of(
          VsiStat.file(isGzip(f) ? uncompressedSize(f) : f.size(), st.get().modified()));
    }
  }

  @Override
  public List<String> list(Vsi vsi, String dir) throws IOException {
    return vsi.list(inner(dir));
  }

  @Override
  public void delete(Vsi vsi, String path) throws IOException {
    vsi.delete(inner(path));
  }

  @Override
  public void rename(Vsi vsi, String from, String to) throws IOException {
    vsi.rename(inner(from), inner(to));
  }

  /** Sequential gzip writer. */
  private static final class GzipWriter implements VsiFile {
    private final GZIPOutputStream out;
    private long written;
    private boolean open = true;

    GzipWriter(VsiFile target) throws IOException {
      this.out = new GZIPOutputStream(Channels.newOutputStream(target), 64 * 1024);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
      if (!open) throw new ClosedChannelException();
      int n = src.remaining();
      byte[] b = new byte[n];
      src.get(b);
      out.write(b);
      written += n;
      return n;
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
      if (position != written) throw new IOException("/vsigzip/ files are written sequentially");
      return write(src);
    }

    @Override
    public int read(ByteBuffer dst) {
      throw new NonReadableChannelException();
    }

    @Override
    public int read(ByteBuffer dst, long position) {
      throw new NonReadableChannelException();
    }

    @Override
    public long position() {
      return written;
    }

    @Override
    public VsiFile position(long newPosition) throws IOException {
      if (newPosition != written) throw new IOException("/vsigzip/ files are written sequentially");
      return this;
    }

    @Override
    public long size() {
      return written;
    }

    @Override
    public VsiFile truncate(long size) throws IOException {
      throw new IOException("Cannot truncate a /vsigzip/ file");
    }

    @Override
    public void setLength(long length) throws IOException {
      throw new IOException("Cannot resize a /vsigzip/ file");
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() throws IOException {
      if (!open) return;
      open = false;
      out.close();
    }
  }
}
