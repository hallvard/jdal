package dev.jdal.vsi;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The default handler: the local file system. */
final class LocalHandler implements VsiHandler {

  @Override
  public List<String> prefixes() {
    return List.of();
  }

  private static Path path(String p) {
    return Path.of(p);
  }

  @Override
  public VsiFile open(Vsi vsi, String p, VsiMode mode, Map<String, String> options)
      throws IOException {
    Set<StandardOpenOption> o = EnumSet.noneOf(StandardOpenOption.class);
    switch (mode) {
      case READ -> o.add(StandardOpenOption.READ);
      case WRITE ->
          o.addAll(
              List.of(
                  StandardOpenOption.WRITE,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.TRUNCATE_EXISTING));
      case UPDATE -> o.addAll(List.of(StandardOpenOption.READ, StandardOpenOption.WRITE));
      case APPEND -> o.addAll(List.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE));
      case WRITE_UPDATE ->
          o.addAll(
              List.of(
                  StandardOpenOption.READ,
                  StandardOpenOption.WRITE,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.TRUNCATE_EXISTING));
    }
    FileChannel ch = FileChannel.open(path(p), o);
    if (Files.isDirectory(path(p))) {
      ch.close();
      throw new IOException("Is a directory: " + p);
    }
    if (mode == VsiMode.APPEND) ch.position(ch.size());
    return new ChannelFile(ch);
  }

  @Override
  public Optional<VsiStat> stat(Vsi vsi, String p) throws IOException {
    try {
      BasicFileAttributes a = Files.readAttributes(path(p), BasicFileAttributes.class);
      return Optional.of(
          a.isDirectory()
              ? new VsiStat(true, 0, a.lastModifiedTime().toMillis())
              : VsiStat.file(a.size(), a.lastModifiedTime().toMillis()));
    } catch (NoSuchFileException e) {
      return Optional.empty();
    }
  }

  @Override
  public List<String> list(Vsi vsi, String dir) throws IOException {
    List<String> names = new ArrayList<>();
    try (DirectoryStream<Path> s = Files.newDirectoryStream(path(dir))) {
      for (Path q : s) names.add(q.getFileName().toString());
    }
    names.sort(null);
    return names;
  }

  @Override
  public void delete(Vsi vsi, String p) throws IOException {
    Files.delete(path(p));
  }

  @Override
  public void mkdir(Vsi vsi, String dir) throws IOException {
    Files.createDirectory(path(dir));
  }

  @Override
  public void rename(Vsi vsi, String from, String to) throws IOException {
    Files.move(path(from), path(to), StandardCopyOption.REPLACE_EXISTING);
  }

  @Override
  public boolean isLocal() {
    return true;
  }
}
