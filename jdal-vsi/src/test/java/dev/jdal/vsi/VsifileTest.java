package dev.jdal.vsi;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.testkit.Port;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NonReadableChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Port of the parts of autotest/gcore/vsifile.py that apply to local files and /vsimem/. */
class VsifileTest {

  static final Vsi VSI = Vsi.global();

  @TempDir Path tmp;

  /** A fresh directory on the file system under test: /vsimem/<unique> or a temp directory. */
  String dir(boolean mem) throws IOException {
    if (!mem) return tmp.toString();
    String d = "/vsimem/" + UUID.randomUUID();
    VSI.mkdir(d);
    return d;
  }

  static void write(VsiFile f, String s) throws IOException {
    ByteBuffer b = ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    while (b.hasRemaining()) f.write(b);
  }

  static String read(VsiFile f, int n) throws IOException {
    ByteBuffer b = ByteBuffer.allocate(n);
    while (b.hasRemaining() && f.read(b) >= 0) {
      // fill
    }
    return new String(b.array(), 0, b.position(), StandardCharsets.ISO_8859_1);
  }

  @Port({
    "autotest/gcore/vsifile.py::test_vsifile_vsimem",
    "autotest/gcore/vsifile.py::test_vsifile_regular_filesystem"
  })
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void genericFileOperations(boolean mem) throws IOException {
    String d = dir(mem);
    String filename = d + "/vsifile.bin";
    long start = System.currentTimeMillis();

    try (VsiFile fp = VSI.open(filename, VsiMode.of("wb+"))) {
      write(fp, "0123456789");
      fp.setLength(20);
      assertEquals(10, fp.position());
      fp.setLength(5);
      assertEquals(10, fp.position());
      fp.position(fp.size());
      assertEquals(5, fp.position());
      write(fp, "XX");
    }
    VsiStat st = VSI.stat(filename).orElseThrow();
    assertEquals(7, st.size());
    assertTrue(st.isFile());
    assertTrue(Math.abs(st.modified() - start) < 2000 + 1000, "mtime " + st.modified());

    try (VsiFile fp = VSI.open(filename, VsiMode.of("rb"))) {
      assertEquals("01234XX", read(fp, 7));
      assertEquals("", read(fp, 1));
      assertThrows(Exception.class, () -> fp.write(ByteBuffer.wrap(new byte[] {'a'})));
      assertThrows(Exception.class, () -> fp.setLength(0));
    }

    // append on an existing file
    try (VsiFile fp = VSI.open(filename, VsiMode.of("ab"))) {
      write(fp, "XX");
    }
    assertEquals(9, VSI.stat(filename).orElseThrow().size());

    VSI.delete(filename);
    assertTrue(VSI.stat(filename).isEmpty());

    // append on a file that does not exist
    try (VsiFile fp = VSI.open(filename, VsiMode.of("ab"))) {
      write(fp, "XX");
    }
    assertEquals(2, VSI.stat(filename).orElseThrow().size());
    VSI.delete(filename);

    // reading a file opened for writing fails
    try (VsiFile fp = VSI.open(filename, VsiMode.of("wb"))) {
      assertThrows(NonReadableChannelException.class, () -> fp.read(ByteBuffer.allocate(1)));
    }

    assertEquals(List.of("vsifile.bin"), VSI.list(d));
    VSI.delete(filename);

    assertThrows(NoSuchFileException.class, () -> VSI.deleteRecursive(filename + "/i_dont_exist"));
    String subdir = filename + "/subdir";
    VSI.mkdirs(subdir + "/subsubdir");
    assertTrue(VSI.exists(subdir));
    assertTrue(VSI.exists(subdir + "/subsubdir"));
    if (!mem) {
      assertThrows(IOException.class, () -> VSI.delete(subdir)); // not empty
      assertTrue(VSI.exists(subdir));
    }
    VSI.deleteRecursive(d);
    assertFalse(VSI.exists(subdir));
    assertFalse(VSI.exists(subdir + "/subsubdir"));
  }

  @Port("autotest/gcore/vsifile.py::test_vsifile_4")
  @Test
  void largeReadsReturnWhatThereIs() throws IOException {
    byte[] data = new byte[100_000];
    VSI.writeBytes("/vsimem/vsifile_4.bin", data);
    try (VsiFile fp = VSI.open("/vsimem/vsifile_4.bin", VsiMode.READ)) {
      assertEquals(100_000, read(fp, 1_000_000).length());
      fp.position(0);
      assertEquals(100_000, read(fp, 1_000_000).length());
    }
    VSI.delete("/vsimem/vsifile_4.bin");
  }

  @Port("autotest/gcore/vsifile.py::test_vsifile_7")
  @Test
  void seekingFarBeyondTheEnd() throws IOException {
    String f = "/vsimem/vsifile_7.bin";
    try (VsiFile fp = VSI.open(f, VsiMode.WRITE)) {
      fp.position(0x7FFFFFFFFFFFFFFFL);
      assertEquals(0, VSI.stat(f).orElseThrow().size());
      assertThrows(IOException.class, () -> fp.write(ByteBuffer.wrap(new byte[] {'a'})));
      assertEquals(0, VSI.stat(f).orElseThrow().size());
    }
    try (VsiFile fp = VSI.open(f, VsiMode.READ)) {
      fp.position(0x7FFFFFFFFFFFFFFFL);
      assertEquals(0x7FFFFFFFFFFFFFFFL, fp.position());
      assertEquals(-1, fp.read(ByteBuffer.allocate(1)));
    }
    VSI.delete(f);
  }

  @Port("autotest/gcore/vsifile.py::test_vsifile_8")
  @Test
  void renamingADirectoryInVsimem() throws IOException {
    VSI.mkdir("/vsimem/mydir8");
    VSI.open("/vsimem/mydir8/a", VsiMode.WRITE).close();
    VSI.rename("/vsimem/mydir8", "/vsimem/newdir8");
    assertTrue(VSI.exists("/vsimem/newdir8"));
    assertTrue(VSI.exists("/vsimem/newdir8/a"));
    assertFalse(VSI.exists("/vsimem/mydir8"));
    VSI.delete("/vsimem/newdir8/a");
    VSI.delete("/vsimem/newdir8");
  }

  @Port({
    "autotest/gcore/vsifile.py::test_vsifile_implicit_dir_creation_1",
    "autotest/gcore/vsifile.py::test_vsifile_implicit_dir_creation_2"
  })
  @Test
  void implicitDirectories() throws IOException {
    String d = dir(true);
    assertTrue(VSI.isDirectory(d));
    VSI.open(d + "/subdir1/subdir2/subdir3/myfile.txt", VsiMode.WRITE).close();
    assertTrue(VSI.isDirectory(d + "/subdir1"));
    assertTrue(VSI.isDirectory(d + "/subdir1/subdir2"));
    assertTrue(VSI.isDirectory(d + "/subdir1/subdir2/subdir3"));

    VSI.open(d + "/afile", VsiMode.WRITE).close();
    assertThrows(IOException.class, () -> VSI.open(d + "/afile/anotherfile", VsiMode.WRITE));
  }

  @Port("autotest/gcore/vsifile.py::test_vsifile_9")
  @Test
  void listingADirectory() throws IOException {
    VSI.mkdir("/vsimem/mydir9");
    for (int i = 0; i < 10; i++) VSI.open("/vsimem/mydir9/" + i, VsiMode.WRITE).close();
    assertEquals(10, VSI.list("/vsimem/mydir9").size());
    assertTrue(VSI.list("/vsimem/mydir9").contains("7"));
    VSI.deleteRecursive("/vsimem/mydir9");
    assertTrue(VSI.list(dev.jdal.testkit.Autotest.root().toString()).size() >= 4);
  }

  @Port("autotest/gcore/vsifile.py::test_vsifile_vsimem_truncate_zeroize")
  @Test
  void truncatedDataIsZeroed() throws IOException {
    try (VsiFile f = VSI.open("/vsimem/test_zeroize.bin", VsiMode.of("wb+"))) {
      byte[] data = new byte[10000];
      java.util.Arrays.fill(data, (byte) 0xff);
      f.write(ByteBuffer.wrap(data));
      f.setLength(0);
      f.position(10000);
      f.write(ByteBuffer.wrap(new byte[] {0}));
      f.position(0);
      ByteBuffer b = ByteBuffer.allocate(1);
      f.read(b);
      assertEquals(0, b.get(0));
    }
    VSI.delete("/vsimem/test_zeroize.bin");
  }

  @Port("autotest/gcore/vsifile.py::test_vsifile_movefile")
  @Test
  void movingAFile() throws IOException {
    VSI.writeBytes("/vsimem/move_src.bin", "foo".getBytes(StandardCharsets.UTF_8));
    VSI.rename("/vsimem/move_src.bin", "/vsimem/move_out.bin");
    assertEquals(3, VSI.stat("/vsimem/move_out.bin").orElseThrow().size());
    assertTrue(VSI.stat("/vsimem/move_src.bin").isEmpty());
    VSI.delete("/vsimem/move_out.bin");
  }

  @Port("autotest/gcore/vsifile.py::test_vsifile_move")
  @Test
  void movingBetweenLocalAndMemory() throws IOException {
    Path local = tmp.resolve("a.txt");
    java.nio.file.Files.writeString(local, "hello");
    VSI.rename(local.toString(), "/vsimem/moved_from_local.txt");
    assertEquals("hello", VSI.readString("/vsimem/moved_from_local.txt"));
    assertFalse(java.nio.file.Files.exists(local));
    VSI.rename("/vsimem/moved_from_local.txt", local.toString());
    assertEquals("hello", java.nio.file.Files.readString(local));
    assertFalse(VSI.exists("/vsimem/moved_from_local.txt"));
  }

  @Port("autotest/gcore/vsifile.py::test_vsifile_rename_on_top_of_existing_file")
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void renameReplacesTheTarget(boolean mem) throws IOException {
    String d = dir(mem);
    VSI.writeBytes(d + "/dest", new byte[] {'a'});
    VSI.writeBytes(d + "/source", new byte[] {'b', 'c'});
    VSI.rename(d + "/source", d + "/dest");
    assertEquals(2, VSI.stat(d + "/dest").orElseThrow().size());
    assertFalse(VSI.exists(d + "/source"));
  }

  @Port("autotest/gcore/vsifile.py::test_vsimem_illegal_filename")
  @Test
  void illegalVsimemName() {
    assertThrows(IOException.class, () -> VSI.open("/vsimem/\\\\", VsiMode.WRITE));
  }

  @Port("autotest/gcore/vsifile.py::test_vsifile_class_file_does_not_exist")
  @Test
  void missingFile() {
    assertThrows(
        NoSuchFileException.class,
        () -> VSI.open(tmp.resolve("nope.txt").toString(), VsiMode.READ));
    assertThrows(NoSuchFileException.class, () -> VSI.open("/vsimem/nope.txt", VsiMode.READ));
  }

  @Port("autotest/gcore/vsifile.py::test_vsifile_use_closed_file")
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void closedFilesCannotBeUsed(boolean mem) throws IOException {
    VsiFile f = VSI.open(dir(mem) + "/file.txt", VsiMode.WRITE);
    write(f, "0123456789");
    f.close();
    assertFalse(f.isOpen());
    assertThrows(Exception.class, () -> f.position(0));
    assertThrows(Exception.class, () -> f.write(ByteBuffer.wrap(new byte[1])));
  }

  @Port("autotest/gcore/vsifile.py::test_vsifile_class_append")
  @Test
  void appending() throws IOException {
    String f = "/vsimem/append_out.txt";
    VSI.writeString(f, "abc");
    try (var w = VSI.newWriter(f, VsiMode.APPEND, StandardCharsets.UTF_8)) {
      w.write("def");
    }
    assertEquals("abcdef", VSI.readString(f));
    VSI.delete(f);
  }

  @Port("autotest/gcore/vsifile.py::test_vsifile_stat_directory_trailing_slash")
  @Test
  void statOfDirectoryWithTrailingSlash() throws IOException {
    assertTrue(VSI.isDirectory(tmp + "/"));
    VSI.mkdir("/vsimem/dirslash");
    assertTrue(VSI.isDirectory("/vsimem/dirslash/"));
    assertTrue(VSI.isDirectory("/vsimem"));
    assertTrue(VSI.isDirectory("/vsimem/"));
    VSI.delete("/vsimem/dirslash");
  }

  @Test
  void routing() {
    assertTrue(VSI.handler("/vsimem/x") instanceof MemHandler);
    assertTrue(VSI.handler("/vsimem") instanceof MemHandler);
    assertTrue(VSI.handler("/vsimem\\x") instanceof MemHandler);
    assertFalse(VSI.handler("/tmp/x") instanceof MemHandler);
    assertTrue(VSI.isLocal("/tmp/x"));
    assertFalse(VSI.isLocal("/vsimem/x"));
    assertEquals("a/b", VsiPath.join("a", "b"));
    assertEquals("/vsimem/a", VsiPath.parent("/vsimem/a/b"));
    assertEquals("b", VsiPath.baseName("/x/b.shp"));
    assertEquals("/x/b.dbf", VsiPath.withExtension("/x/b.shp", "dbf"));
  }

  @Test
  void duplicatePrefixesAreRejected() {
    assertThrows(IllegalStateException.class, () -> Vsi.of(new MemHandler(), new MemHandler()));
  }
}
