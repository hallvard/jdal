package dev.jdal.filter;

/**
 * The outcome of translating a filter.
 *
 * @param text the translated condition, or null if nothing could be translated
 * @param complete true if the text stands for the whole filter, so that a service applying it
 *     returns exactly the matching features; otherwise it is only a pre-selection and the filter
 *     must still be applied to what comes back
 */
public record Translation(String text, boolean complete) {

  public static final Translation NONE = new Translation(null, false);

  public boolean present() {
    return text != null;
  }
}
