package dev.jdal.vsi;

import static org.junit.jupiter.api.Assertions.*;

import dev.jdal.testkit.Autotest;
import dev.jdal.testkit.Port;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ports of autotest/gcore/vsizip.py and the gzip, subfile and stdin/stdout parts of vsifile.py. */
class ArchiveTest {

  static final Vsi VSI = Vsi.global();

  @TempDir Path tmp;

  static void put(String path, String text) throws IOException {
    try (VsiFile f = VSI.open(path, VsiMode.WRITE)) {
      f.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
    }
  }

  static String get(String path) throws IOException {
    return VSI.readString(path);
  }

  // ------------------------------------------------------------------ zip

  @Port("autotest/gcore/vsizip.py::test_vsizip_1")
  @Test
  void writingAndReadingAZip() throws IOException {
    VsiFile zip = VSI.open("/vsizip/vsimem/test1.zip", VsiMode.WRITE);
    VSI.open("/vsizip/vsimem/test1.zip/subdir2/", VsiMode.WRITE)
        .close(); // one way to create a directory
    VSI.mkdir("/vsizip/vsimem/test1.zip/subdir1");
    put("/vsizip/vsimem/test1.zip/subdir3/abcd", "abcd");

    IOException e =
        assertThrows(
            IOException.class,
            () -> VSI.open("/vsizip/vsimem/test1.zip/subdir3/abcd", VsiMode.READ));
    assertEquals("Cannot read a zip file being written", e.getMessage());

    VsiFile f3 = VSI.open("/vsizip/vsimem/test1.zip/subdir3/efghi", VsiMode.WRITE);
    f3.write(ByteBuffer.wrap("efghi".getBytes(StandardCharsets.UTF_8)));
    e =
        assertThrows(
            IOException.class,
            () -> VSI.open("/vsizip/vsimem/test1.zip/that_wont_work", VsiMode.WRITE));
    assertEquals(
        "Cannot create that_wont_work while another file is being written in the .zip",
        e.getMessage());
    f3.close();
    zip.close();

    assertThrows(
        IOException.class,
        () -> VSI.open("/vsizip/vsimem/test1.zip", VsiMode.READ)); // more than 1 file
    assertEquals("abcd", get("/vsizip/vsimem/test1.zip/subdir3/abcd"));

    // alternate syntax with braces
    VSI.rename("/vsimem/test1.zip", "/vsimem/test1.xxx");
    assertEquals("abcd", get("/vsizip/{/vsimem/test1.xxx}/subdir3/abcd"));
    assertTrue(VSI.exists("/vsizip/{/vsimem/test1.xxx}/subdir3/abcd/")); // trailing slash
    assertEquals(3, VSI.list("/vsizip/{/vsimem/test1.xxx}").size());

    assertThrows(
        IOException.class,
        () -> VSI.open("/vsizip/{/vsimem/test1.xxx", VsiMode.READ)); // unbalanced
    assertThrows(
        IOException.class,
        () -> VSI.open("/vsizip/{/vsimem/test1.xxx}/bla", VsiMode.READ)); // no such member
    assertThrows(
        IOException.class,
        () -> VSI.open("/vsizip/{/vsimem/test1.zzz}/bla", VsiMode.READ)); // no archive
    assertThrows(
        IOException.class, () -> VSI.open("/vsizip/{/vsimem/test1.xxx}.aux.xml", VsiMode.READ));

    // a zip in a zip
    VsiFile outer = VSI.open("/vsizip/{/vsimem/zipinzip1.yyy}", VsiMode.WRITE);
    VSI.writeBytes(
        "/vsizip/{/vsimem/zipinzip1.yyy}/test.xxx", VSI.readAllBytes("/vsimem/test1.xxx"));
    outer.close();
    assertEquals("abcd", get("/vsizip/{/vsizip/{/vsimem/zipinzip1.yyy}/test.xxx}/subdir3/abcd/"));

    VSI.delete("/vsimem/test1.xxx");
    VSI.delete("/vsimem/zipinzip1.yyy");
    assertTrue(VSI.stat("/vsizip//vsimem/foo.zip").isEmpty());
    assertThrows(IOException.class, () -> VSI.list("/vsizip//vsimem/foo.zip"));
  }

  @Port("autotest/gcore/vsizip.py::test_vsizip_2")
  @Test
  void appendingToAZip() throws IOException {
    String zip = "/vsizip//vsimem/test2.zip";
    put(zip + "/foo.bar", "12345");
    assertEquals(List.of("foo.bar"), VSI.list(zip));

    VsiFile f = VSI.open(zip + "/bar.baz", VsiMode.WRITE);
    f.write(ByteBuffer.wrap("67890".getBytes(StandardCharsets.UTF_8)));
    IOException e = assertThrows(IOException.class, () -> VSI.list(zip));
    assertEquals("Cannot read a zip file being written", e.getMessage());
    f.close();

    assertEquals(List.of("foo.bar", "bar.baz"), VSI.list(zip));
    assertEquals("12345", get(zip + "/foo.bar"));
    assertEquals("67890", get(zip + "/bar.baz"));
    VSI.delete("/vsimem/test2.zip");
  }

  @Port("autotest/gcore/vsizip.py::test_vsizip_3")
  @Test
  void writingToAZipThatWasListed() throws IOException {
    String base = "/vsizip/" + tmp + "/test3.zip";
    VsiFile main = VSI.open(base, VsiMode.WRITE);
    put(base + "/foo", "foo");
    put(base + "/bar", "bar");
    main.close();
    VSI.list(base);
    put(base + "/baz", "baz");
    assertEquals(List.of("foo", "bar", "baz"), VSI.list(base));
  }

  @Port({"autotest/gcore/vsizip.py::test_vsizip_4"})
  @Test
  void recursiveListing() throws IOException {
    String zip = "/vsizip/" + Autotest.data("gcore/data/testzip.zip");
    List<String> res = VSI.listRecursive(zip);
    assertEquals(
        List.of(
            "subdir/",
            "subdir/subdir/",
            "subdir/subdir/uint16.tif",
            "subdir/subdir/test_rpc.txt",
            "subdir/test_rpc.txt",
            "test_rpc.txt",
            "uint16.tif"),
        res);
    assertEquals(res, VSI.listRecursive(zip + "/"));
  }

  @Port("autotest/gcore/vsizip.py::test_vsizip_5")
  @Test
  void deepDirectories() throws IOException {
    VsiFile main = VSI.open("/vsizip/vsimem/bigdepthzip.zip", VsiMode.WRITE);
    VSI.open("/vsizip/vsimem/bigdepthzip.zip/a" + "/a".repeat(1000), VsiMode.WRITE).close();
    main.close();
    List<String> res = VSI.listRecursive("/vsizip/vsimem/bigdepthzip.zip");
    assertEquals(1001, res.size());
    assertEquals("a/a/a/a/a/a/a/a/a/a/a/", res.get(10));
    VSI.delete("/vsimem/bigdepthzip.zip");
  }

  @Port("autotest/gcore/vsizip.py::test_vsizip_6")
  @Test
  void sameNameTwice() throws IOException {
    VsiFile main = VSI.open("/vsizip/vsimem/test6.zip", VsiMode.WRITE);
    put("/vsizip/vsimem/test6.zip/foo.bar", "12345");
    assertThrows(
        IOException.class, () -> VSI.open("/vsizip/vsimem/test6.zip/foo.bar", VsiMode.WRITE));
    main.close();
    VSI.delete("/vsimem/test6.zip");

    put("/vsizip/vsimem/test6.zip/foo.bar", "12345");
    assertThrows(
        IOException.class, () -> VSI.open("/vsizip/vsimem/test6.zip/foo.bar", VsiMode.WRITE));
    VSI.delete("/vsimem/test6.zip");
  }

  static final List<String> RUSSIAN = List.of("абвгде", "жзийкл");

  @Port({"autotest/gcore/vsizip.py::test_vsizip_7", "autotest/gcore/vsizip.py::test_vsizip_11"})
  @Test
  void utf8NamesInZip() throws IOException {
    assertEquals(RUSSIAN, VSI.list("/vsizip/" + Autotest.data("gcore/data/cp866_plus_utf8.zip")));
    assertEquals(RUSSIAN, VSI.list("/vsizip/" + Autotest.data("gcore/data/utf8.zip")));
  }

  @Port("autotest/gcore/vsizip.py::test_vsizip_10")
  @Test
  void recodingNamesFromAnotherEncoding() throws IOException {
    String old = System.getProperty("CPL_ZIP_ENCODING");
    System.setProperty("CPL_ZIP_ENCODING", "CP866");
    try {
      assertEquals(RUSSIAN, VSI.list("/vsizip/" + Autotest.data("gcore/data/cp866.zip")));
    } finally {
      if (old == null) System.clearProperty("CPL_ZIP_ENCODING");
      else System.setProperty("CPL_ZIP_ENCODING", old);
    }
  }

  @Port("autotest/gcore/vsizip.py::test_vsizip_12")
  @Test
  void changingTheContentOfAZip() throws IOException {
    for (String[] c :
        new String[][] {{"src1", "foo.bar", "0123456"}, {"src2", "bar.baz", "01234567"}}) {
      VsiFile main = VSI.open("/vsizip/vsimem/vsizip_12_" + c[0] + ".zip", VsiMode.WRITE);
      put("/vsizip/vsimem/vsizip_12_" + c[0] + ".zip/" + c[1], c[2]);
      main.close();
    }
    VSI.writeBytes("/vsimem/vsizip_12.zip", VSI.readAllBytes("/vsimem/vsizip_12_src1.zip"));
    assertEquals(List.of("foo.bar"), VSI.list("/vsizip/vsimem/vsizip_12.zip"));
    VSI.writeBytes("/vsimem/vsizip_12.zip", VSI.readAllBytes("/vsimem/vsizip_12_src2.zip"));
    assertEquals(List.of("bar.baz"), VSI.list("/vsizip/vsimem/vsizip_12.zip"));
    for (String n : List.of("vsizip_12_src1", "vsizip_12_src2", "vsizip_12"))
      VSI.delete("/vsimem/" + n + ".zip");
  }

  @Port("autotest/gcore/vsizip.py::test_vsizip_13")
  @Test
  void manyEntries() throws IOException {
    VsiFile main = VSI.open("/vsizip/vsimem/vsizip_13.zip", VsiMode.WRITE);
    for (int i = 0; i < 10; i++)
      VSI.open("/vsizip/vsimem/vsizip_13.zip/" + i, VsiMode.WRITE).close();
    main.close();
    assertEquals(10, VSI.list("/vsizip/vsimem/vsizip_13.zip").size());
    VSI.delete("/vsimem/vsizip_13.zip");
  }

  @Port("autotest/gcore/vsizip.py::test_vsizip_14")
  @Test
  void writingNonLatinNames() throws IOException {
    VsiFile main = VSI.open("/vsizip//vsimem/vsizip_14.zip", VsiMode.WRITE);
    put("/vsizip//vsimem/vsizip_14.zip/абвгде", "hello");
    main.close();
    assertEquals(List.of("абвгде"), VSI.list("/vsizip//vsimem/vsizip_14.zip"));
    assertEquals("hello", get("/vsizip//vsimem/vsizip_14.zip/абвгде"));
    VSI.delete("/vsimem/vsizip_14.zip");
  }

  @Port({"autotest/gcore/vsizip.py::test_vsizip_byte_sozip"})
  @Test
  void seekingInsideACompressedEntry() throws IOException {
    VsiFile main = VSI.open("/vsizip/vsimem/seek.zip", VsiMode.WRITE);
    byte[] data = new byte[300_000];
    for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31 + i / 7);
    VSI.writeBytes("/vsizip/vsimem/seek.zip/data.bin", data);
    main.close();
    try (VsiFile f = VSI.open("/vsizip/vsimem/seek.zip/data.bin", VsiMode.READ)) {
      assertEquals(data.length, f.size());
      ByteBuffer b = ByteBuffer.allocate(10);
      f.read(b, 200_000);
      assertArrayEquals(Arrays.copyOfRange(data, 200_000, 200_010), b.array());
      b.clear();
      f.read(b, 5); // backwards
      assertArrayEquals(Arrays.copyOfRange(data, 5, 15), b.array());
      assertEquals(VSI.stat("/vsizip/vsimem/seek.zip/data.bin").orElseThrow().size(), data.length);
    }
    VSI.delete("/vsimem/seek.zip");
  }

  @Test
  void readingZipsWithOneFile() throws IOException {
    // a zip with a single file can be opened without naming the member
    String zip = "/vsizip/" + Autotest.data("gcore/data/byte.tif.zip");
    try (VsiFile f = VSI.open(zip, VsiMode.READ)) {
      assertTrue(f.size() > 0);
    }
    assertTrue(VSI.exists("/vsizip/" + Autotest.data("gcore/data/stored.zip")));
  }

  // ------------------------------------------------------------------ gzip

  @Port("autotest/gcore/vsifile.py::test_vsigzip_multi_thread")
  @Test
  void gzipRoundTrip() throws IOException {
    try (VsiFile f = VSI.open("/vsigzip//vsimem/gz_roundtrip.gz", VsiMode.WRITE)) {
      for (int i = 0; i < 100_000; i++)
        f.write(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)));
    }
    assertTrue(VSI.stat("/vsimem/gz_roundtrip.gz").orElseThrow().size() < 5000); // compressed
    assertEquals(500_000, VSI.stat("/vsigzip//vsimem/gz_roundtrip.gz").orElseThrow().size());
    assertEquals("hello".repeat(100_000), get("/vsigzip//vsimem/gz_roundtrip.gz"));
    try (VsiFile f = VSI.open("/vsigzip//vsimem/gz_roundtrip.gz", VsiMode.READ)) {
      ByteBuffer b = ByteBuffer.allocate(5);
      f.read(b, 250_000);
      assertEquals("hello", new String(b.array(), StandardCharsets.UTF_8));
    }
    VSI.delete("/vsimem/gz_roundtrip.gz");
  }

  @Test
  void gzipOfAPlainFileReadsTheFile() throws IOException {
    put("/vsimem/not_gzipped.txt", "plain text");
    assertEquals("plain text", get("/vsigzip//vsimem/not_gzipped.txt"));
    assertEquals(10, VSI.stat("/vsigzip//vsimem/not_gzipped.txt").orElseThrow().size());
    VSI.delete("/vsimem/not_gzipped.txt");
  }

  @Port("autotest/gcore/vsifile.py::test_vsifile_15")
  @Test
  void corruptedGzipReadsWhatIsValidThenFails() throws IOException {
    try (VsiFile fp =
        VSI.open(
            "/vsigzip/" + Autotest.data("gcore/data/corrupted_z_buf_error.gz"), VsiMode.READ)) {
      long total = 0;
      ByteBuffer b = ByteBuffer.allocate(4);
      IOException error = null;
      while (error == null) {
        b.clear();
        try {
          int n = fp.read(b);
          if (n < 0) break;
          total += n;
        } catch (IOException e) {
          error = e;
        }
      }
      assertNotNull(error);
      assertEquals(6469, total);
      // seeking back to the start clears the error
      fp.position(0);
      long again = 0;
      b.clear();
      while (true) {
        b.clear();
        try {
          int n = fp.read(b);
          if (n < 0) break;
          again += n;
        } catch (IOException e) {
          break;
        }
      }
      assertEquals(6469, again);
    }
  }

  // ------------------------------------------------------------------ subfile, stdin, stdout

  @Port("autotest/gcore/vsifile.py::test_vsifile_11")
  @Test
  void extendingAFileThroughASubfile() throws IOException {
    VSI.open("/vsimem/vsifile_11", VsiMode.WRITE).close();
    try (VsiFile f = VSI.open("/vsisubfile/0_,/vsimem/vsifile_11", VsiMode.of("r+"))) {
      f.write(ByteBuffer.wrap("0123456789".getBytes(StandardCharsets.UTF_8)));
      f.setLength(10 + 4096 + 2);
      assertEquals(10, f.position());
      assertThrows(IOException.class, () -> f.setLength(0));
    }
    byte[] data = VSI.readAllBytes("/vsimem/vsifile_11");
    assertEquals(10 + 4096 + 2, data.length);
    assertEquals('0', data[0]);
    assertEquals('9', data[9]);
    assertEquals(0, data[10]);
    assertEquals(0, data[data.length - 1]);
    VSI.delete("/vsimem/vsifile_11");
  }

  @Test
  void subfileRanges() throws IOException {
    put("/vsimem/range.txt", "0123456789");
    assertEquals("23456", get("/vsisubfile/2_5,/vsimem/range.txt"));
    assertEquals("23456789", get("/vsisubfile/2,/vsimem/range.txt"));
    assertEquals("23456789", get("/vsisubfile/2_-1,/vsimem/range.txt"));
    assertEquals(5, VSI.stat("/vsisubfile/2_5,/vsimem/range.txt").orElseThrow().size());
    assertThrows(
        IOException.class,
        () -> VSI.open("/vsisubfile/2_5/vsimem/range.txt", VsiMode.READ)); // missing comma
    VSI.delete("/vsimem/range.txt");
  }

  @Port("autotest/gcore/vsifile.py::test_vsifile_13")
  @Test
  void prefixesWithoutTrailingSlash() {
    for (String p : List.of("/vsigzip", "/vsizip", "/vsimem", "/vsisubfile")) {
      assertDoesNotThrow(
          () -> {
            try {
              VSI.open(p, VsiMode.READ).close();
            } catch (IOException e) {
              // expected: there is nothing to open
            }
            VSI.stat(p);
          },
          p);
    }
  }

  @Test
  void standardInputAndOutput() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Vsi vsi =
        Vsi.of(
            new StdinHandler(
                () -> new ByteArrayInputStream("line1\nline2\n".getBytes(StandardCharsets.UTF_8))),
            new StdoutHandler(() -> out));
    try (VsiFile in = vsi.open("/vsistdin/", VsiMode.READ)) {
      ByteBuffer b = ByteBuffer.allocate(5);
      in.read(b);
      assertEquals("line1", new String(b.array(), StandardCharsets.UTF_8));
      in.position(6);
      b.clear();
      in.read(b);
      assertEquals("line2", new String(b.array(), StandardCharsets.UTF_8));
      assertEquals(12, in.size());
    }
    try (VsiFile in = vsi.open("/vsistdin/", VsiMode.READ)) { // can be read again
      assertEquals("line1\nline2\n", new String(Channels_readAll(in), StandardCharsets.UTF_8));
    }
    try (VsiFile o = vsi.open("/vsistdout/", VsiMode.WRITE)) {
      o.write(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)));
      assertEquals(5, o.position());
      assertThrows(IOException.class, () -> o.position(0));
    }
    assertEquals("hello", out.toString(StandardCharsets.UTF_8));
    assertThrows(IOException.class, () -> vsi.open("/vsistdout/", VsiMode.READ));
  }

  private static byte[] Channels_readAll(VsiFile f) throws IOException {
    ByteBuffer b = ByteBuffer.allocate((int) f.size());
    while (b.hasRemaining() && f.read(b) >= 0) {
      // fill
    }
    return b.array();
  }
}
