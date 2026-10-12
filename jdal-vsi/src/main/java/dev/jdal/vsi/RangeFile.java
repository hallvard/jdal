package dev.jdal.vsi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;

/**
 * A window on another file: bytes {@code [offset, offset + length)}, or to the end of the file if
 * {@code length} is 0.
 */
final class RangeFile implements VsiFile {

  private final VsiFile inner;
  private final long offset;
  private final long length;
  private final boolean writable;
  private long position;
  private boolean open = true;

  RangeFile(VsiFile inner, long offset, long length, boolean writable) {
    this.inner = inner;
    this.offset = offset;
    this.length = length;
    this.writable = writable;
  }

  private void check() throws IOException {
    if (!open) throw new ClosedChannelException();
  }

  private long room(long pos) {
    return length == 0 ? Long.MAX_VALUE : length - pos;
  }

  @Override
  public int read(ByteBuffer dst) throws IOException {
    int n = read(dst, position);
    if (n > 0) position += n;
    return n;
  }

  @Override
  public int read(ByteBuffer dst, long pos) throws IOException {
    check();
    long room = room(pos);
    if (room <= 0) return -1;
    if (dst.remaining() > room) dst = dst.slice(dst.position(), (int) room);
    return inner.read(dst, offset + pos);
  }

  @Override
  public int write(ByteBuffer src) throws IOException {
    int n = write(src, position);
    position += n;
    return n;
  }

  @Override
  public int write(ByteBuffer src, long pos) throws IOException {
    check();
    if (!writable) throw new NonWritableChannelException();
    long room = room(pos);
    if (room <= 0 || (length != 0 && src.remaining() > room)) {
      throw new IOException("Write beyond the end of the sub file");
    }
    return inner.write(src, offset + pos);
  }

  @Override
  public long position() throws IOException {
    check();
    return position;
  }

  @Override
  public RangeFile position(long newPosition) throws IOException {
    check();
    position = newPosition;
    return this;
  }

  @Override
  public long size() throws IOException {
    check();
    return length != 0 ? length : Math.max(0, inner.size() - offset);
  }

  @Override
  public VsiFile truncate(long size) throws IOException {
    if (!writable) throw new NonWritableChannelException();
    if (size < size()) throw new IOException("A sub file cannot be shrunk");
    return this;
  }

  /** Only growing an open-ended range is possible; it extends the underlying file. */
  @Override
  public void setLength(long newLength) throws IOException {
    check();
    if (!writable) throw new NonWritableChannelException();
    if (length != 0 || newLength < size())
      throw new IOException("A sub file cannot be shrunk or resized");
    inner.setLength(offset + newLength);
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void close() throws IOException {
    if (!open) return;
    open = false;
    inner.close();
  }
}
