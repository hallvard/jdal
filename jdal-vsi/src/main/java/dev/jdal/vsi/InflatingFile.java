package dev.jdal.vsi;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.function.LongSupplier;

/**
 * A read-only file over a decompressing stream. Seeking forward skips; seeking backward starts the
 * stream again.
 */
final class InflatingFile extends ReadOnlyVsiFile {

  /** Opens the decompressed stream from the start. */
  @FunctionalInterface
  interface Source {
    InputStream open() throws IOException;
  }

  private final Source source;
  private final LongSupplier size;
  private final Closeable owner;
  private InputStream in;
  private long cursor;
  private long knownSize = -1;
  private IOException pending;

  InflatingFile(Source source, LongSupplier size, Closeable owner) {
    this.source = source;
    this.size = size;
    this.owner = owner;
  }

  @Override
  protected int readAt(ByteBuffer dst, long pos) throws IOException {
    if (!dst.hasRemaining()) return 0;
    if (pos == cursor && pending != null) throw pending;
    if (in == null || pos < cursor) {
      pending = null;
      if (in != null) in.close();
      in = source.open();
      cursor = 0;
    }
    if (pos > cursor) {
      cursor += skip(in, pos - cursor);
      if (cursor < pos) return -1;
    }
    byte[] buf = new byte[Math.min(dst.remaining(), 64 * 1024)];
    int total = 0;
    while (dst.hasRemaining()) {
      int n;
      try {
        n = in.read(buf, 0, Math.min(buf.length, dst.remaining()));
      } catch (IOException e) {
        // deliver what was decompressed before the error, fail on the next read
        if (total == 0) throw e;
        pending = e;
        break;
      }
      if (n < 0) break;
      dst.put(buf, 0, n);
      total += n;
      cursor += n;
      if (in.available() <= 0 && total > 0) break;
    }
    return total == 0 ? -1 : total;
  }

  /** Skips up to {@code n} bytes by reading; returns how many were skipped. */
  private static long skip(InputStream in, long n) throws IOException {
    byte[] scratch = new byte[64 * 1024];
    long done = 0;
    while (done < n) {
      int k = in.read(scratch, 0, (int) Math.min(scratch.length, n - done));
      if (k < 0) break;
      done += k;
    }
    return done;
  }

  @Override
  public long size() throws IOException {
    if (knownSize < 0) knownSize = size.getAsLong();
    return knownSize;
  }

  @Override
  protected void closeResources() throws IOException {
    try {
      if (in != null) in.close();
    } finally {
      owner.close();
    }
  }
}
