package dev.jdal.vsi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/** An open file: a seekable channel that can also be read at an absolute position. */
public interface VsiFile extends SeekableByteChannel {

  /** Reads at {@code position} without changing the channel position; -1 at end of file. */
  int read(ByteBuffer dst, long position) throws IOException;

  /**
   * Writes at {@code position} without changing the channel position, extending the file if needed.
   */
  int write(ByteBuffer src, long position) throws IOException;

  /**
   * Sets the file length, extending with zeros or cutting. Unlike {@link #truncate} the position is
   * left alone (GDAL's {@code VSIFTruncateL}).
   */
  void setLength(long length) throws IOException;
}
