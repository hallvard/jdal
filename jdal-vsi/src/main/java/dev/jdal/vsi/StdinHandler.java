package dev.jdal.vsi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /vsistdin/}: the standard input as a read-only file. What has been read is kept in memory
 * so that the file can be read again from the start and seeked in; the size is only known once the
 * input has been read to the end.
 */
public final class StdinHandler implements VsiHandler {

  private final Supplier<? extends InputStream> stream;
  private InputStream in;
  private byte[] data = new byte[8192];
  private int length;
  private boolean eof;

  public StdinHandler() {
    this(() -> System.in);
  }

  public StdinHandler(Supplier<? extends InputStream> stream) {
    this.stream = stream;
  }

  @Override
  public List<String> prefixes() {
    return List.of("/vsistdin/");
  }

  @Override
  public VsiFile open(Vsi vsi, String path, VsiMode mode, Map<String, String> options)
      throws IOException {
    if (mode != VsiMode.READ) throw new IOException("/vsistdin/ can only be read");
    return new In();
  }

  @Override
  public Optional<VsiStat> stat(Vsi vsi, String path) {
    return Optional.of(path.equals("/vsistdin/") ? VsiStat.dir() : VsiStat.file(0, 0));
  }

  /** Makes sure bytes up to {@code upTo} are buffered, as far as the input goes. */
  private synchronized void fill(long upTo) throws IOException {
    if (in == null) in = stream.get();
    while (!eof && length < upTo) {
      if (length == data.length) data = Arrays.copyOf(data, data.length * 2);
      int n = in.read(data, length, data.length - length);
      if (n < 0) eof = true;
      else length += n;
    }
  }

  private final class In extends ReadOnlyVsiFile {
    @Override
    protected int readAt(ByteBuffer dst, long pos) throws IOException {
      if (!dst.hasRemaining()) return 0;
      synchronized (StdinHandler.this) {
        fill(Math.min(Integer.MAX_VALUE - 8, pos + dst.remaining()));
        if (pos >= length) return -1;
        int n = (int) Math.min(dst.remaining(), length - pos);
        dst.put(data, (int) pos, n);
        return n;
      }
    }

    @Override
    public long size() throws IOException {
      synchronized (StdinHandler.this) {
        fill(Long.MAX_VALUE);
        return length;
      }
    }
  }
}
