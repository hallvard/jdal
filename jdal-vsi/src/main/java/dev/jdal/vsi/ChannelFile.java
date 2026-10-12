package dev.jdal.vsi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** A {@link VsiFile} on a local file channel. */
final class ChannelFile implements VsiFile {

  private final FileChannel ch;

  ChannelFile(FileChannel ch) {
    this.ch = ch;
  }

  @Override
  public int read(ByteBuffer dst) throws IOException {
    return ch.read(dst);
  }

  @Override
  public int read(ByteBuffer dst, long position) throws IOException {
    return ch.read(dst, position);
  }

  @Override
  public int write(ByteBuffer src) throws IOException {
    return ch.write(src);
  }

  @Override
  public int write(ByteBuffer src, long position) throws IOException {
    return ch.write(src, position);
  }

  @Override
  public long position() throws IOException {
    return ch.position();
  }

  @Override
  public ChannelFile position(long newPosition) throws IOException {
    ch.position(newPosition);
    return this;
  }

  @Override
  public long size() throws IOException {
    return ch.size();
  }

  @Override
  public ChannelFile truncate(long size) throws IOException {
    ch.truncate(size);
    return this;
  }

  @Override
  public void setLength(long length) throws IOException {
    long pos = ch.position();
    long size = ch.size();
    if (length < size) {
      ch.truncate(length);
      ch.position(pos);
    } else if (length > size) {
      ch.write(ByteBuffer.wrap(new byte[1]), length - 1);
    }
  }

  @Override
  public boolean isOpen() {
    return ch.isOpen();
  }

  @Override
  public void close() throws IOException {
    ch.close();
  }
}
