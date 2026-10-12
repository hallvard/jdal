package dev.jdal.core;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Declaration of a driver option (open, dataset creation or layer creation option): used for
 * validation and completion in front ends. {@code values} is empty unless the option takes one of a
 * fixed set of values.
 */
public record OptionDef(String name, String description, String defaultValue, List<String> values) {

  public OptionDef {
    values = List.copyOf(values);
  }

  public static OptionDef bool(String name, boolean defaultValue, String description) {
    return new OptionDef(name, description, defaultValue ? "YES" : "NO", List.of("YES", "NO"));
  }

  public static OptionDef choice(
      String name, String defaultValue, String description, String... values) {
    return new OptionDef(name, description, defaultValue, List.of(values));
  }

  public static OptionDef string(String name, String description) {
    return new OptionDef(name, description, null, List.of());
  }

  public static OptionDef string(String name, String defaultValue, String description) {
    return new OptionDef(name, description, defaultValue, List.of());
  }

  /** An error message if {@code value} is not acceptable for this option. */
  public Optional<String> check(String value) {
    if (values.isEmpty()) return Optional.empty();
    String v = value.toUpperCase(Locale.ROOT);
    boolean ok = values.stream().anyMatch(a -> a.equalsIgnoreCase(v));
    return ok
        ? Optional.empty()
        : Optional.of(
            "Value '"
                + value
                + "' is not valid for option "
                + name
                + ", expected one of "
                + values);
  }

  /**
   * Checks {@code KEY=VALUE} pairs against the declared options; returns one message per unknown
   * key or invalid value (GDAL reports these as warnings).
   */
  public static List<String> problems(
      String what, String driver, List<OptionDef> defs, java.util.Map<String, String> given) {
    java.util.ArrayList<String> out = new java.util.ArrayList<>();
    given.forEach(
        (k, v) -> {
          Optional<OptionDef> d = defs.stream().filter(x -> x.name.equalsIgnoreCase(k)).findFirst();
          if (d.isEmpty()) {
            out.add("Driver " + driver + " does not support " + k + " " + what + " option");
          } else {
            d.get().check(v).ifPresent(out::add);
          }
        });
    return out;
  }
}
