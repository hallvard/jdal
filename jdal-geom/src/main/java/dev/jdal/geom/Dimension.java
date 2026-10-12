package dev.jdal.geom;

/** Which ordinates a geometry carries besides X and Y. */
public enum Dimension {
  XY(2, false, false),
  XYZ(3, true, false),
  XYM(3, false, true),
  XYZM(4, true, true);

  private final int size;
  private final boolean z;
  private final boolean m;

  Dimension(int size, boolean z, boolean m) {
    this.size = size;
    this.z = z;
    this.m = m;
  }

  /** Number of ordinates per coordinate. */
  public int size() {
    return size;
  }

  public boolean hasZ() {
    return z;
  }

  public boolean hasM() {
    return m;
  }

  public static Dimension of(boolean hasZ, boolean hasM) {
    return hasZ ? (hasM ? XYZM : XYZ) : (hasM ? XYM : XY);
  }

  /** The widest of two dimensions (union of their ordinates). */
  public Dimension union(Dimension other) {
    return of(hasZ() || other.hasZ(), hasM() || other.hasM());
  }

  /** ISO WKB type-code offset: 0, 1000, 2000 or 3000. */
  public int isoOffset() {
    return (z ? 1000 : 0) + (m ? 2000 : 0);
  }

  static Dimension fromIsoOffset(int offset) {
    return switch (offset) {
      case 0 -> XY;
      case 1000 -> XYZ;
      case 2000 -> XYM;
      case 3000 -> XYZM;
      default -> throw new IllegalArgumentException("Bad WKB type code offset " + offset);
    };
  }
}
