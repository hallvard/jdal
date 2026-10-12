package dev.jdal.vsi;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /vsisubfile/offset_size,path}: a byte range of another file. A size of 0 (or {@code -1})
 * means up to the end of the file.
 */
public final class SubfileHandler implements VsiHandler {

  private static final String PREFIX = "/vsisubfile/";

  private record Parsed(long offset, long size, String path) {}

  @Override
  public List<String> prefixes() {
    return List.of(PREFIX);
  }

  static Parsed parse(String path) throws IOException {
    if (path.length() < PREFIX.length())
      throw new IOException("Invalid /vsisubfile/ path: " + path);
    String rest = path.substring(PREFIX.length());
    long offset = 0;
    long size = 0;
    int i = 0;
    int start = 0;
    boolean haveSize = false;
    while (i < rest.length()) {
      char c = rest.charAt(i);
      if (c == ',') {
        String num = rest.substring(start, i);
        if (haveSize) size = number(num.startsWith("-") ? "0" : num, path);
        else offset = number(num, path);
        return new Parsed(offset, size, rest.substring(i + 1));
      }
      if (c == '_' && !haveSize) {
        offset = number(rest.substring(start, i), path);
        haveSize = true;
        start = i + 1;
      } else if (c == '/') {
        break; // missing comma
      }
      i++;
    }
    throw new IOException("Invalid /vsisubfile/ path (expected offset_size,path): " + path);
  }

  private static long number(String s, String path) throws IOException {
    try {
      return s.isEmpty() ? 0 : Long.parseUnsignedLong(s);
    } catch (NumberFormatException e) {
      throw new IOException("Invalid number '" + s + "' in " + path);
    }
  }

  @Override
  public VsiFile open(Vsi vsi, String path, VsiMode mode, Map<String, String> options)
      throws IOException {
    Parsed p = parse(path);
    VsiFile inner = vsi.open(p.path(), mode, options);
    return new RangeFile(inner, p.offset(), p.size(), mode.writable());
  }

  @Override
  public Optional<VsiStat> stat(Vsi vsi, String path) throws IOException {
    Parsed p = parse(path);
    Optional<VsiStat> st = vsi.stat(p.path());
    if (st.isEmpty()) return st;
    long size = p.size() != 0 ? p.size() : Math.max(0, st.get().size() - p.offset());
    return Optional.of(VsiStat.file(size, st.get().modified()));
  }

  @Override
  public void delete(Vsi vsi, String path) throws IOException {
    throw new IOException("Cannot delete a /vsisubfile/");
  }

  @Override
  public List<String> list(Vsi vsi, String dir) throws IOException {
    throw new NoSuchFileException(dir);
  }
}
