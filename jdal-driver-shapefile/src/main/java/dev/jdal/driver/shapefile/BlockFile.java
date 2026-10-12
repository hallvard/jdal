package dev.jdal.driver.shapefile;

import dev.jdal.core.JdalException;
import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiFile;
import dev.jdal.vsi.VsiMode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Random access to a file with a read window so that sequential reads do not cost one system call
 * each.
 */
final class BlockFile implements AutoCloseable {

  private static final int WINDOW = 64 * 1024;

  private final String path;
  private final VsiFile ch;
  private ByteBuffer window;
  private long windowPos = -1;

  private BlockFile(String path, VsiFile ch) {
    this.path = path;
    this.ch = ch;
  }

  static BlockFile open(String path, boolean write) {
    try {
      return new BlockFile(path, Vsi.global().open(path, write ? VsiMode.UPDATE : VsiMode.READ));
    } catch (IOException e) {
      throw new JdalException("Cannot open " + path + ": " + e.getMessage(), e);
    }
  }

  static BlockFile create(String path) {
    try {
      return new BlockFile(path, Vsi.global().open(path, VsiMode.WRITE_UPDATE));
    } catch (IOException e) {
      throw new JdalException("Cannot create " + path + ": " + e.getMessage(), e);
    }
  }

  long size() {
    try {
      return ch.size();
    } catch (IOException e) {
      throw new JdalException("Cannot read " + path + ": " + e.getMessage(), e);
    }
  }

  /**
   * {@code len} bytes at {@code pos} as a little endian buffer positioned at 0; shorter at the end
   * of the file.
   */
  ByteBuffer read(long pos, int len) {
    try {
      if (window == null || pos < windowPos || pos + len > windowPos + window.limit()) {
        int n = (int) Math.max(len, WINDOW);
        ByteBuffer b = ByteBuffer.allocate(n);
        int read = 0;
        while (read < n) {
          int r = ch.read(b, pos + read);
          if (r < 0) break;
          read += r;
        }
        b.limit(read);
        window = b;
        windowPos = pos;
      }
      int off = (int) (pos - windowPos);
      int avail = Math.max(0, Math.min(len, window.limit() - off));
      return window.slice(off, avail).order(ByteOrder.LITTLE_ENDIAN);
    } catch (IOException e) {
      throw new JdalException("Cannot read " + path + ": " + e.getMessage(), e);
    }
  }

  void write(long pos, ByteBuffer src) {
    try {
      window = null;
      long p = pos;
      while (src.hasRemaining()) p += ch.write(src, p);
    } catch (IOException e) {
      throw new JdalException("Cannot write " + path + ": " + e.getMessage(), e);
    }
  }

  void truncate(long size) {
    try {
      window = null;
      if (ch.size() > size) ch.setLength(size);
    } catch (IOException e) {
      throw new JdalException("Cannot write " + path + ": " + e.getMessage(), e);
    }
  }

  /** Nothing to do: writes go straight to the file. */
  void force() {}

  @Override
  public void close() {
    try {
      ch.close();
    } catch (IOException e) {
      throw new JdalException("Cannot close " + path + ": " + e.getMessage(), e);
    }
  }
}
