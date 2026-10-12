package dev.jdal.vsi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * An input stream over a range of a {@link VsiFile}, read by absolute position so the file's own
 * position is untouched.
 */
final class PositionalInputStream extends InputStream {

  private final VsiFile file;
  private long pos;
  private final long end;

  PositionalInputStream(VsiFile file, long start, long length) {
    this.file = file;
    this.pos = start;
    this.end = start + length;
  }

  @Override
  public int read() throws IOException {
    byte[] b = new byte[1];
    return read(b, 0, 1) < 0 ? -1 : b[0] & 0xFF;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    if (len == 0) return 0;
    long remaining = end - pos;
    if (remaining <= 0) return -1;
    int n = file.read(ByteBuffer.wrap(b, off, (int) Math.min(len, remaining)), pos);
    if (n > 0) pos += n;
    return n;
  }

  @Override
  public long skip(long n) {
    long k = Math.max(0, Math.min(n, end - pos));
    pos += k;
    return k;
  }

  @Override
  public int available() {
    return (int) Math.min(Integer.MAX_VALUE, end - pos);
  }
}
