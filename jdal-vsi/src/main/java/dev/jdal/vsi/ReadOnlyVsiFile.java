package dev.jdal.vsi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;

/** Base for read-only files: implement {@link #readAt} and {@link #size}. */
public abstract class ReadOnlyVsiFile implements VsiFile {

  private long position;
  private boolean open = true;

  /** Reads at {@code pos}; -1 at the end of the file. */
  protected abstract int readAt(ByteBuffer dst, long pos) throws IOException;

  protected void closeResources() throws IOException {}

  private void check() throws IOException {
    if (!open) throw new ClosedChannelException();
  }

  @Override
  public int read(ByteBuffer dst) throws IOException {
    check();
    int n = readAt(dst, position);
    if (n > 0) position += n;
    return n;
  }

  @Override
  public int read(ByteBuffer dst, long pos) throws IOException {
    check();
    return readAt(dst, pos);
  }

  @Override
  public int write(ByteBuffer src) {
    throw new NonWritableChannelException();
  }

  @Override
  public int write(ByteBuffer src, long pos) {
    throw new NonWritableChannelException();
  }

  @Override
  public void setLength(long length) {
    throw new NonWritableChannelException();
  }

  @Override
  public VsiFile truncate(long size) {
    throw new NonWritableChannelException();
  }

  @Override
  public long position() throws IOException {
    check();
    return position;
  }

  @Override
  public VsiFile position(long newPosition) throws IOException {
    check();
    if (newPosition < 0) throw new IllegalArgumentException("negative position");
    position = newPosition;
    return this;
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void close() throws IOException {
    if (!open) return;
    open = false;
    closeResources();
  }
}
