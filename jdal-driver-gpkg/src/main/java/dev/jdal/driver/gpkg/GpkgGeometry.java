package dev.jdal.driver.gpkg;

import dev.jdal.geom.Envelope;
import dev.jdal.geom.Geometry;
import dev.jdal.geom.Point;
import dev.jdal.geom.Wkb;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** The GeoPackage binary geometry format: a small header followed by standard WKB. */
final class GpkgGeometry {

  private GpkgGeometry() {}

  /** Decodes a blob; returns null for an empty-flagged geometry with no WKB payload. */
  static Geometry decode(byte[] blob) {
    if (blob == null) return null;
    if (blob.length < 8 || blob[0] != 'G' || blob[1] != 'P') {
      throw new IllegalArgumentException("Not a GeoPackage geometry blob");
    }
    int flags = blob[3] & 0xFF;
    int envelopeIndicator = (flags >> 1) & 0x7;
    int envelopeBytes =
        switch (envelopeIndicator) {
          case 0 -> 0;
          case 1 -> 32;
          case 2, 3 -> 48;
          case 4 -> 64;
          default ->
              throw new IllegalArgumentException("Bad envelope indicator " + envelopeIndicator);
        };
    int offset = 8 + envelopeBytes;
    if (offset > blob.length)
      throw new IllegalArgumentException("Truncated GeoPackage geometry header");
    return Wkb.read(ByteBuffer.wrap(blob, offset, blob.length - offset).slice());
  }

  static int srsId(byte[] blob) {
    ByteBuffer b = ByteBuffer.wrap(blob, 4, 4);
    b.order((blob[3] & 1) != 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    return b.getInt();
  }

  static byte[] encode(Geometry g, int srsId) {
    byte[] wkb = Wkb.write(g);
    boolean empty = g.isEmpty();
    boolean withEnvelope = !empty && !(g instanceof Point);
    int envBytes = withEnvelope ? 32 : 0;
    ByteBuffer b = ByteBuffer.allocate(8 + envBytes + wkb.length).order(ByteOrder.LITTLE_ENDIAN);
    b.put((byte) 'G').put((byte) 'P').put((byte) 0);
    int flags = 1 | ((withEnvelope ? 1 : 0) << 1) | ((empty ? 1 : 0) << 4);
    b.put((byte) flags);
    b.putInt(srsId);
    if (withEnvelope) {
      Envelope e = g.envelope();
      b.putDouble(e.minX()).putDouble(e.maxX()).putDouble(e.minY()).putDouble(e.maxY());
    }
    b.put(wkb);
    return Arrays.copyOf(b.array(), b.position());
  }
}
