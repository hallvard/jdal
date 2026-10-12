package dev.jdal.vsi;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Serves all paths that start with one of its {@link #prefixes()}. Handlers are found with {@link
 * java.util.ServiceLoader}; a wrapping handler (gzip, zip) opens the inner path through the {@link
 * Vsi} it is given, so handlers nest ({@code /vsigzip//vsimem/x.gz}).
 *
 * <p>Handlers must be thread safe; open files need not be.
 */
public interface VsiHandler {

  /** The prefixes served, e.g. {@code /vsimem/}. The longest matching prefix wins. */
  List<String> prefixes();

  /**
   * Opens {@code path} (prefix included); throws {@link java.nio.file.NoSuchFileException} if it is
   * missing.
   */
  VsiFile open(Vsi vsi, String path, VsiMode mode, Map<String, String> options) throws IOException;

  Optional<VsiStat> stat(Vsi vsi, String path) throws IOException;

  /** The names (not paths) of the entries of a directory. */
  default List<String> list(Vsi vsi, String dir) throws IOException {
    throw new UnsupportedOperationException("list is not supported for " + dir);
  }

  /** Deletes a file or an empty directory. */
  default void delete(Vsi vsi, String path) throws IOException {
    throw new UnsupportedOperationException("delete is not supported for " + path);
  }

  /** Creates one directory; the parent exists. */
  default void mkdir(Vsi vsi, String dir) throws IOException {
    throw new UnsupportedOperationException("mkdir is not supported for " + dir);
  }

  /** Renames within this handler, replacing the target. */
  default void rename(Vsi vsi, String from, String to) throws IOException {
    throw new UnsupportedOperationException("rename is not supported for " + from);
  }

  /**
   * The names of the files next to {@code path}, or null if not known (callers then list the
   * directory).
   */
  default List<String> siblings(Vsi vsi, String path) throws IOException {
    return null;
  }

  /** True for the local disk. */
  default boolean isLocal() {
    return false;
  }
}
