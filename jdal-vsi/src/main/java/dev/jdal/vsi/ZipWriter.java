package dev.jdal.vsi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes entries to a zip file one at a time. Data is deflated straight into the file, the sizes in
 * the local header are filled in afterwards, and the central directory is written when the writer
 * is finished. Existing archives are extended in place: new entries replace the old central
 * directory, which is rewritten after them.
 */
final class ZipWriter {

  private static final long MAX = 0xFFFFFFFEL; // no zip64 when writing

  private final VsiFile file;
  private final Set<String> names = new HashSet<>();
  private final java.io.ByteArrayOutputStream central = new java.io.ByteArrayOutputStream();
  private long end;
  private int count;
  private boolean entryOpen;
  private boolean finished;

  private ZipWriter(VsiFile file) {
    this.file = file;
  }

  /** A new archive (an existing file is replaced). */
  static ZipWriter create(Vsi vsi, String path) throws IOException {
    return new ZipWriter(vsi.open(path, VsiMode.WRITE_UPDATE));
  }

  /** Appends to an existing archive. */
  static ZipWriter append(Vsi vsi, String path) throws IOException {
    VsiFile f = vsi.open(path, VsiMode.UPDATE);
    try {
      ZipIndex idx = ZipIndex.read(f);
      ZipWriter w = new ZipWriter(f);
      ByteBuffer raw = ByteBuffer.allocate((int) idx.centralSize);
      ZipIndex.readFully(f, raw, idx.centralOffset);
      w.central.write(raw.array(), 0, raw.limit());
      w.end = idx.centralOffset;
      w.count = idx.entries.size();
      for (ZipIndex.Entry e : idx.entries) w.names.add(e.name());
      return w;
    } catch (IOException | RuntimeException e) {
      f.close();
      throw e;
    }
  }

  boolean entryOpen() {
    return entryOpen;
  }

  boolean contains(String name) {
    return names.contains(name);
  }

  /** Starts an entry; a name ending in a slash is a directory and is complete immediately. */
  VsiFile addEntry(String name) throws IOException {
    if (entryOpen)
      throw new IOException(
          "Cannot create " + name + " while another file is being written in the .zip");
    if (!names.add(name)) throw new IOException(name + " already exists in the .zip");
    entryOpen = true;
    Entry e = new Entry(name);
    e.writeLocalHeader();
    if (name.endsWith("/")) e.close();
    return e;
  }

  /** Writes the central directory and closes the file. */
  void finish() throws IOException {
    if (finished) return;
    finished = true;
    try {
      long cdOffset = end;
      byte[] cd = central.toByteArray();
      ByteBuffer b = ByteBuffer.allocate(cd.length + 22).order(ByteOrder.LITTLE_ENDIAN);
      b.put(cd);
      b.putInt(ZipIndex.EOCD_SIG).putShort((short) 0).putShort((short) 0);
      b.putShort((short) Math.min(count, 0xFFFF)).putShort((short) Math.min(count, 0xFFFF));
      b.putInt(cd.length).putInt((int) cdOffset).putShort((short) 0);
      b.flip();
      file.write(b, cdOffset);
      file.setLength(cdOffset + cd.length + 22);
    } finally {
      file.close();
    }
  }

  private static int dosDate(LocalDateTime t) {
    return ((t.getYear() - 1980) << 9) | (t.getMonthValue() << 5) | t.getDayOfMonth();
  }

  private static int dosTime(LocalDateTime t) {
    return (t.getHour() << 11) | (t.getMinute() << 5) | (t.getSecond() / 2);
  }

  /** The file handle for one entry: write-only and sequential. */
  private final class Entry implements VsiFile {
    private final String name;
    private final byte[] nameBytes;
    private final long headerOffset;
    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    private final CRC32 crc = new CRC32();
    private final byte[] out = new byte[32 * 1024];
    private final LocalDateTime time = LocalDateTime.now();
    private long size;
    private long compressed;
    private boolean open = true;
    private final boolean directory;

    Entry(String name) {
      this.name = name;
      this.nameBytes = name.getBytes(StandardCharsets.UTF_8);
      this.headerOffset = end;
      this.directory = name.endsWith("/");
    }

    void writeLocalHeader() throws IOException {
      ByteBuffer h = ByteBuffer.allocate(30 + nameBytes.length).order(ByteOrder.LITTLE_ENDIAN);
      h.putInt(ZipIndex.LOC_SIG).putShort((short) 20).putShort((short) 0x0800);
      h.putShort((short) (directory ? 0 : 8));
      h.putShort((short) dosTime(time)).putShort((short) dosDate(time));
      h.putInt(0).putInt(0).putInt(0);
      h.putShort((short) nameBytes.length).putShort((short) 0).put(nameBytes);
      h.flip();
      file.write(h, headerOffset);
      end = headerOffset + h.limit();
    }

    private void emit() throws IOException {
      int n;
      while ((n = deflater.deflate(out)) > 0) {
        file.write(ByteBuffer.wrap(out, 0, n), end);
        end += n;
        compressed += n;
        if (compressed > MAX) throw new IOException("zip64 is not supported when writing");
      }
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
      if (!open) throw new ClosedChannelException();
      if (directory) throw new IOException("Cannot write data to a directory entry");
      int n = src.remaining();
      byte[] b = new byte[n];
      src.get(b);
      size += n;
      if (size > MAX) throw new IOException("zip64 is not supported when writing");
      crc.update(b);
      deflater.setInput(b);
      while (!deflater.needsInput()) emit();
      return n;
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
      if (position != size) throw new IOException("Zip entries are written sequentially");
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
      return size;
    }

    @Override
    public VsiFile position(long newPosition) throws IOException {
      if (newPosition != size) throw new IOException("Zip entries cannot seek while writing");
      return this;
    }

    @Override
    public long size() {
      return size;
    }

    @Override
    public VsiFile truncate(long newSize) throws IOException {
      throw new IOException("Cannot truncate a zip entry");
    }

    @Override
    public void setLength(long length) throws IOException {
      throw new IOException("Cannot resize a zip entry");
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() throws IOException {
      if (!open) return;
      open = false;
      try {
        if (!directory) {
          deflater.finish();
          while (!deflater.finished()) emit();
          ByteBuffer patch = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
          patch.putInt((int) crc.getValue()).putInt((int) compressed).putInt((int) size).flip();
          file.write(patch, headerOffset + 14);
        }
        ByteBuffer c = ByteBuffer.allocate(46 + nameBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        c.putInt(ZipIndex.CEN_SIG)
            .putShort((short) 20)
            .putShort((short) 20)
            .putShort((short) 0x0800);
        c.putShort((short) (directory ? 0 : 8));
        c.putShort((short) dosTime(time)).putShort((short) dosDate(time));
        c.putInt((int) crc.getValue()).putInt((int) compressed).putInt((int) size);
        c.putShort((short) nameBytes.length).putShort((short) 0).putShort((short) 0);
        c.putShort((short) 0).putShort((short) 0).putInt(directory ? 0x10 : 0);
        c.putInt((int) headerOffset).put(nameBytes);
        central.write(c.array(), 0, c.limit());
        count++;
      } finally {
        deflater.end();
        entryOpen = false;
      }
    }
  }
}
