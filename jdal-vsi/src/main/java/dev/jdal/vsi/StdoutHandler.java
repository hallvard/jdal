package dev.jdal.vsi;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /vsistdout/}: write-only, sequential; closing the file flushes but does not close the
 * stream.
 */
public final class StdoutHandler implements VsiHandler {

  private final Supplier<? extends OutputStream> stream;

  public StdoutHandler() {
    this(() -> System.out);
  }

  public StdoutHandler(Supplier<? extends OutputStream> stream) {
    this.stream = stream;
  }

  @Override
  public List<String> prefixes() {
    return List.of("/vsistdout/");
  }

  @Override
  public VsiFile open(Vsi vsi, String path, VsiMode mode, Map<String, String> options)
      throws IOException {
    if (mode != VsiMode.WRITE && mode != VsiMode.APPEND) {
      throw new IOException("/vsistdout/ can only be written");
    }
    return new Out(stream.get());
  }

  @Override
  public Optional<VsiStat> stat(Vsi vsi, String path) {
    return path.equals("/vsistdout/")
        ? Optional.of(VsiStat.dir())
        : Optional.of(VsiStat.file(0, 0));
  }

  private static final class Out implements VsiFile {
    private final OutputStream out;
    private long written;
    private boolean open = true;

    Out(OutputStream out) {
      this.out = out;
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
      if (position != written) throw new IOException("/vsistdout/ is written sequentially");
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
      if (newPosition != written) throw new IOException("/vsistdout/ cannot seek");
      return this;
    }

    @Override
    public long size() {
      return written;
    }

    @Override
    public VsiFile truncate(long size) throws IOException {
      throw new IOException("/vsistdout/ cannot be truncated");
    }

    @Override
    public void setLength(long length) throws IOException {
      throw new IOException("/vsistdout/ cannot be resized");
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() throws IOException {
      if (!open) return;
      open = false;
      out.flush();
      if (out instanceof PrintStream ps) ps.flush();
    }
  }
}
