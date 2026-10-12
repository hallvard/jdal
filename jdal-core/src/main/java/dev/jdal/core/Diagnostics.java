package dev.jdal.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Warnings that do not stop an operation (GDAL's {@code CPLError(CE_Warning, ...)}). By default
 * they go to {@link System.Logger}; {@link #capture} redirects them for the code run inside it,
 * which tests use to assert on warnings.
 */
public final class Diagnostics {

  private static final ScopedValue<Consumer<String>> HANDLER = ScopedValue.newInstance();
  private static final System.Logger LOG = System.getLogger("dev.jdal");

  private Diagnostics() {}

  public static void warn(String message) {
    if (HANDLER.isBound()) HANDLER.get().accept(message);
    else LOG.log(System.Logger.Level.WARNING, message);
  }

  /** Runs {@code body} and returns the warnings it issued (in this thread). */
  public static List<String> capture(Runnable body) {
    List<String> out = new ArrayList<>();
    ScopedValue.where(HANDLER, (Consumer<String>) out::add).run(body);
    return out;
  }
}
