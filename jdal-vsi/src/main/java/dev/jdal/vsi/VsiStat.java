package dev.jdal.vsi;

/**
 * What {@link VsiHandler#stat} reports about a path. {@code modified} is in epoch milliseconds, 0
 * if unknown.
 */
public record VsiStat(boolean directory, long size, long modified) {

  public static VsiStat file(long size, long modified) {
    return new VsiStat(false, size, modified);
  }

  public static VsiStat dir() {
    return new VsiStat(true, 0, 0);
  }

  public boolean isFile() {
    return !directory;
  }
}
