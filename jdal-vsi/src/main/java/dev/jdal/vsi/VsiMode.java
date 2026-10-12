package dev.jdal.vsi;

/** How a file is opened, after the fopen modes GDAL uses ("rb", "wb", "r+b", "ab", "w+b"). */
public enum VsiMode {
  /** Existing file, read only. */
  READ,
  /** Create or truncate, write only. */
  WRITE,
  /** Existing file, read and write. */
  UPDATE,
  /** Create if needed, write at the end. */
  APPEND,
  /** Create or truncate, read and write. */
  WRITE_UPDATE;

  public boolean readable() {
    return this == READ || this == UPDATE || this == WRITE_UPDATE;
  }

  public boolean writable() {
    return this != READ;
  }

  /** Parses a C {@code fopen} mode string. */
  public static VsiMode of(String access) {
    boolean plus = access.indexOf('+') >= 0;
    if (access.indexOf('a') >= 0) return APPEND;
    if (access.indexOf('w') >= 0) return plus ? WRITE_UPDATE : WRITE;
    if (access.indexOf('r') >= 0) return plus ? UPDATE : READ;
    throw new IllegalArgumentException("Invalid access mode '" + access + "'");
  }
}
