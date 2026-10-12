package dev.jdal.core;

/**
 * Failure of an operation on a dataset, layer or driver. Unchecked, replacing GDAL's {@code
 * CPLError}/{@code OGRErr}.
 */
public class JdalException extends RuntimeException {

  public JdalException(String message) {
    super(message);
  }

  public JdalException(String message, Throwable cause) {
    super(message, cause);
  }
}
