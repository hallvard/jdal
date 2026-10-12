package dev.jdal.srs;

/**
 * A coordinate could not be transformed (outside the domain of the projection, missing datum shift,
 * ...).
 */
public class TransformException extends RuntimeException {

  public TransformException(String message, Throwable cause) {
    super(message, cause);
  }
}
