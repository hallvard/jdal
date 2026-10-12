package dev.jdal.vsi.curl;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jdal.testkit.Port;
import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiFile;
import dev.jdal.vsi.VsiMode;
import dev.jdal.vsi.ZipHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ports of autotest/gcore/vsicurl.py against a local HTTP server instead of GDAL's scripted mock
 * server.
 */
class CurlTest {

  /** A request as the server saw it. */
  record Req(String method, String path, String range) {
    @Override
    public String toString() {
      return method + " " + path + (range == null ? "" : " [" + range + "]");
    }
  }

  interface Route {
    void handle(HttpExchange x, Req r) throws IOException;
  }

  HttpServer server;
  final List<Req> log = Collections.synchronizedList(new ArrayList<>());
  final Map<String, Route> routes = new HashMap<>();
  final Map<String, String> config = new HashMap<>();
  CurlHandler curl;
  Vsi vsi;
  String base;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        x -> {
          Req r =
              new Req(
                  x.getRequestMethod(),
                  x.getRequestURI().getPath(),
                  x.getRequestHeaders().getFirst("Range"));
          log.add(r);
          Route route = routes.get(r.path());
          try {
            if (route == null) reply(x, 404, Map.of(), "not found");
            else route.handle(x, r);
          } finally {
            x.close();
          }
        });
    server.start();
    base = "/vsicurl/http://localhost:" + server.getAddress().getPort();
    curl = new CurlHandler(config::get);
    vsi = Vsi.of(curl, new ZipHandler());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  static void reply(HttpExchange x, int status, Map<String, String> headers, String body)
      throws IOException {
    reply(x, status, headers, body.getBytes(StandardCharsets.UTF_8));
  }

  static void reply(HttpExchange x, int status, Map<String, String> headers, byte[] body)
      throws IOException {
    headers.forEach((k, v) -> x.getResponseHeaders().add(k, v));
    if (x.getRequestMethod().equals("HEAD")) {
      x.getResponseHeaders().add("Content-Length", String.valueOf(body.length));
      x.sendResponseHeaders(status, -1);
      return;
    }
    x.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    try (OutputStream out = x.getResponseBody()) {
      out.write(body);
    }
  }

  /** Serves {@code data} with proper range support. */
  Route file(byte[] data) {
    return (x, r) -> {
      if (r.method().equals("HEAD") || r.range() == null) {
        reply(x, 200, Map.of("Last-Modified", "Sat, 01 Jan 2022 12:00:00 GMT"), data);
        return;
      }
      String[] p = r.range().substring("bytes=".length()).split("-");
      int from = Integer.parseInt(p[0]);
      if (from >= data.length) {
        reply(x, 416, Map.of(), "");
        return;
      }
      int to = Math.min(data.length - 1, Integer.parseInt(p[1]));
      reply(
          x,
          206,
          Map.of("Content-Range", "bytes " + from + "-" + to + "/" + data.length),
          java.util.Arrays.copyOfRange(data, from, to + 1));
    };
  }

  static byte[] pattern(int n) {
    byte[] b = new byte[n];
    for (int i = 0; i < n; i++) b[i] = (byte) (i % 251);
    return b;
  }

  long count(String method) {
    synchronized (log) {
      return log.stream().filter(r -> r.method().equals(method)).count();
    }
  }

  // ------------------------------------------------------------------ tests

  @Test
  void statUsesHead() {
    routes.put("/a.bin", file(pattern(1000)));
    VsiStatCheck st = new VsiStatCheck(vsi.stat(base + "/a.bin").orElseThrow());
    assertEquals(1000, st.size());
    assertTrue(st.file());
    assertEquals(1641038400000L, st.modified());
    assertEquals(List.of("HEAD /a.bin"), log.stream().map(Req::toString).toList());
  }

  record VsiStatCheck(dev.jdal.vsi.VsiStat s) {
    long size() {
      return s.size();
    }

    boolean file() {
      return s.isFile();
    }

    long modified() {
      return s.modified();
    }
  }

  @Port("autotest/gcore/vsicurl.py::test_vsicurl_404_repeated_same_resource")
  @Test
  void missingFilesAreRememberedAndCanBeForgotten() {
    assertTrue(vsi.stat(base + "/does/not/exist.bin").isEmpty());
    assertTrue(vsi.stat(base + "/does/not/exist.bin").isEmpty());
    assertEquals(1, log.size());
    assertThrows(
        java.nio.file.NoSuchFileException.class,
        () -> vsi.open(base + "/does/not/exist.bin", VsiMode.READ));
    assertEquals(1, log.size());
    curl.clearCache(); // test_vsicurl_test_clear_cache
    assertTrue(vsi.stat(base + "/does/not/exist.bin").isEmpty());
    assertEquals(2, log.size());
  }

  @Port("autotest/gcore/vsicurl.py::test_vsicurl_test_fallback_from_head_to_get")
  @Test
  void headNotAllowedFallsBackToGet() {
    routes.put(
        "/fallback",
        (x, r) -> {
          if (r.method().equals("HEAD")) reply(x, 405, Map.of(), "");
          else reply(x, 200, Map.of(), "foo");
        });
    assertEquals(3, vsi.stat(base + "/fallback").orElseThrow().size());
    assertEquals(List.of("HEAD", "GET"), log.stream().map(Req::method).toList());
  }

  @Port("autotest/gcore/vsicurl.py::test_vsicurl_no_size_in_HEAD")
  @Test
  void headWithoutSize() {
    routes.put(
        "/nosize.bin",
        (x, r) -> {
          if (r.method().equals("HEAD")) {
            x.sendResponseHeaders(200, -1); // no Content-Length
          } else {
            reply(x, 200, Map.of(), "X".repeat(10));
          }
        });
    assertEquals(10, vsi.stat(base + "/nosize.bin").orElseThrow().size());
  }

  @Port("autotest/gcore/vsicurl.py::test_vsicurl_head_accept_ranges_but_no_content_length")
  @Test
  void headWithoutContentLengthAsksForARange() {
    routes.put(
        "/ranges",
        (x, r) -> {
          if (r.method().equals("HEAD")) {
            x.getResponseHeaders().add("Accept-Ranges", "bytes");
            x.sendResponseHeaders(200, -1);
          } else {
            reply(x, 206, Map.of("Content-Range", "bytes 0-2/3"), "foo");
          }
        });
    assertEquals(3, vsi.stat(base + "/ranges").orElseThrow().size());
    assertEquals("bytes=0-16383", log.getLast().range());
  }

  @Port("autotest/gcore/vsicurl.py::test_vsicurl_test_CPL_VSIL_CURL_USE_HEAD_NO")
  @Test
  void withoutHead() throws IOException {
    config.put("CPL_VSIL_CURL_USE_HEAD", "NO");
    routes.put("/big.bin", (x, r) -> reply(x, 200, Map.of(), new byte[200_000]));
    try (VsiFile f = vsi.open(base + "/big.bin", VsiMode.READ)) {
      assertEquals(200_000, f.size());
    }
    assertEquals(0, count("HEAD"));
  }

  @Test
  void rangesAreFetchedInChunksAndCached() throws IOException {
    byte[] data = pattern(100_000);
    routes.put("/data.bin", file(data));
    try (VsiFile f = vsi.open(base + "/data.bin", VsiMode.READ)) {
      assertEquals(100_000, f.size());
      ByteBuffer b = ByteBuffer.allocate(10);
      f.read(b, 20_000);
      assertArrayEquals(java.util.Arrays.copyOfRange(data, 20_000, 20_010), b.array());
      assertEquals("bytes=16384-32767", log.getLast().range());
      long gets = count("GET");
      b.clear();
      f.read(b, 20_500); // same chunk: no request
      assertEquals(gets, count("GET"));
      // across a chunk border, and then back again
      ByteBuffer wide = ByteBuffer.allocate(40_000);
      int n = f.read(wide, 10_000);
      assertEquals(40_000, n);
      assertArrayEquals(java.util.Arrays.copyOfRange(data, 10_000, 50_000), wide.array());
      long afterWide = count("GET");
      b.clear();
      f.read(b, 20_000);
      assertEquals(afterWide, count("GET"));
      // end of file
      ByteBuffer tail = ByteBuffer.allocate(100);
      assertEquals(50, f.read(tail, 99_950));
      assertEquals(-1, f.read(tail, 100_000));
    }
  }

  @Test
  void serversThatIgnoreRangeHeaders() throws IOException {
    byte[] data = pattern(100_000);
    routes.put("/norange.bin", (x, r) -> reply(x, 200, Map.of(), data));
    try (VsiFile f = vsi.open(base + "/norange.bin", VsiMode.READ)) {
      ByteBuffer b = ByteBuffer.allocate(20);
      f.read(b, 50_000);
      assertArrayEquals(java.util.Arrays.copyOfRange(data, 50_000, 50_020), b.array());
    }
  }

  @Test
  void redirectsAreFollowed() throws IOException {
    routes.put("/new.bin", file(pattern(500)));
    routes.put(
        "/old.bin",
        (x, r) -> {
          x.getResponseHeaders().add("Location", "/new.bin");
          x.sendResponseHeaders(302, -1);
        });
    try (VsiFile f = vsi.open(base + "/old.bin", VsiMode.READ)) {
      assertEquals(500, f.size());
      ByteBuffer b = ByteBuffer.allocate(5);
      f.read(b, 3);
      assertArrayEquals(new byte[] {3, 4, 5, 6, 7}, b.array());
    }
  }

  @Port("autotest/gcore/vsicurl.py::test_vsicurl_GDAL_HTTP_HEADERS")
  @Test
  void customHeadersAreSent() {
    config.put("GDAL_HTTP_HEADERS", "X-Token: secret\nX-Other: 1");
    List<String> seen = new ArrayList<>();
    routes.put(
        "/h.bin",
        (x, r) -> {
          seen.add(
              x.getRequestHeaders().getFirst("X-Token")
                  + "/"
                  + x.getRequestHeaders().getFirst("X-Other"));
          reply(x, 200, Map.of(), "abc");
        });
    assertEquals(3, vsi.stat(base + "/h.bin").orElseThrow().size());
    assertEquals(List.of("secret/1"), seen);
  }

  @Port("autotest/gcore/vsicurl.py::test_vsicurl_test_parse_html_filelist_apache")
  @Test
  void apacheDirectoryListing() throws IOException {
    routes.put(
        "/mydir/",
        (x, r) ->
            reply(
                x,
                200,
                Map.of("Content-Type", "text/html"),
                """
                <html><body><h1>Index of /mydir</h1><table>
                <tr><th><a href="?C=N;O=D">Name</a></th></tr>
                <tr><td><a href="/gdal/data/">Parent Directory</a></td></tr>
                <tr><td><a href="foo.tif">foo.tif</a></td><td>90K</td></tr>
                <tr><td><a href="foo%20with%20space.tif">foo with space.tif</a></td></tr>
                <tr><td><a href="sub/">sub/</a></td></tr>
                </table></body></html>
                """));
    routes.put("/mydir/foo with space.tif", file(pattern(10)));
    assertEquals(List.of("foo.tif", "foo%20with%20space.tif", "sub"), vsi.list(base + "/mydir"));
    assertTrue(vsi.stat(base + "/mydir/foo%20with%20space.tif").isPresent());
    assertTrue(vsi.stat(base + "/mydir/i_dont_exist").isEmpty());
    assertTrue(vsi.isDirectory(base + "/mydir/"));
  }

  @Test
  void zipFilesOverHttpAreReadWithFewRequests() throws IOException {
    Vsi mem = Vsi.global();
    String zip = "/vsimem/curl_test.zip";
    VsiFile main = mem.open("/vsizip" + zip, VsiMode.WRITE);
    mem.writeBytes(
        "/vsizip" + zip + "/hello.txt", "hello over http".getBytes(StandardCharsets.UTF_8));
    mem.writeBytes("/vsizip" + zip + "/big.bin", pattern(500_000));
    main.close();
    routes.put("/test.zip", file(mem.readAllBytes(zip)));
    mem.delete(zip);

    String archive = "/vsizip/" + base + "/test.zip";
    assertEquals(List.of("hello.txt", "big.bin"), vsi.list(archive));
    assertEquals("hello over http", vsi.readString(archive + "/hello.txt"));
    try (VsiFile f = vsi.open(archive + "/big.bin", VsiMode.READ)) {
      ByteBuffer b = ByteBuffer.allocate(4);
      f.read(b, 400_000);
      assertArrayEquals(
          java.util.Arrays.copyOfRange(pattern(500_000), 400_000, 400_004), b.array());
    }
    assertTrue(count("GET") < 30, "range requests: " + count("GET"));
  }

  @Test
  void readOnly() {
    routes.put("/ro.bin", file(pattern(10)));
    assertThrows(IOException.class, () -> vsi.open(base + "/ro.bin", VsiMode.WRITE));
  }
}
