package dev.jdal.vsi.curl;

import dev.jdal.http.Http;
import dev.jdal.vsi.ReadOnlyVsiFile;
import dev.jdal.vsi.Vsi;
import dev.jdal.vsi.VsiFile;
import dev.jdal.vsi.VsiHandler;
import dev.jdal.vsi.VsiMode;
import dev.jdal.vsi.VsiStat;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.NoSuchFileException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /vsicurl/http://host/path}: read-only access to files on web servers. The size comes from
 * a HEAD request (or a ranged GET if the server does not answer it usefully); data is fetched with
 * range requests in chunks that are kept in an LRU cache. A server that ignores the Range header is
 * handled by keeping the whole response.
 *
 * <p>Settings are read like GDAL's configuration options, from system properties and then the
 * environment: {@code CPL_VSIL_CURL_USE_HEAD} (YES), {@code CPL_VSIL_CURL_CHUNK_SIZE} (16384),
 * {@code CPL_VSIL_CURL_CACHE_SIZE} (16000000), {@code GDAL_HTTP_HEADERS} (one {@code Name: value}
 * per line), {@code GDAL_HTTP_USERAGENT} and {@code GDAL_HTTP_TIMEOUT} (seconds).
 */
public final class CurlHandler implements VsiHandler {

  private static final List<String> PREFIXES = List.of("/vsicurl/", "/vsicurl_streaming/");
  private static final int MAX_WHOLE_BODY = 256 * 1024 * 1024;
  private static final Pattern HREF =
      Pattern.compile("<a\\s+href=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

  /** What is known about one URL. */
  private record Info(boolean exists, boolean directory, long size, long modified, byte[] whole) {
    static final Info MISSING = new Info(false, false, 0, 0, null);
  }

  private final Http http;
  private final Map<String, Info> infos = new ConcurrentHashMap<>();
  private final LinkedHashMap<String, byte[]> chunks = new LinkedHashMap<>(64, 0.75f, true);
  private long cachedBytes;

  public CurlHandler() {
    this(Http.global());
  }

  public CurlHandler(Function<String, String> config) {
    this(new Http(config));
  }

  public CurlHandler(Http http) {
    this.http = http;
  }

  private String option(String key, String fallback) {
    return http.option(key, fallback);
  }

  private long number(String key, long fallback) {
    return http.number(key, fallback);
  }

  /** Forgets file sizes and downloaded data (GDAL's {@code VSICurlClearCache}). */
  public synchronized void clearCache() {
    infos.clear();
    chunks.clear();
    cachedBytes = 0;
  }

  @Override
  public List<String> prefixes() {
    return PREFIXES;
  }

  private static String url(String path) {
    for (String p : PREFIXES) if (path.startsWith(p)) return path.substring(p.length());
    return path.startsWith("/vsicurl") ? path.substring("/vsicurl".length()) : path;
  }

  // ------------------------------------------------------------------ HTTP

  private HttpRequest.Builder request(String url) throws IOException {
    return http.request(url);
  }

  private HttpResponse<InputStream> send(HttpRequest r) throws IOException {
    return http.send(r);
  }

  private static long modified(HttpHeaders h) {
    return h.firstValue("Last-Modified")
        .map(
            v -> {
              try {
                return ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli();
              } catch (RuntimeException e) {
                return 0L;
              }
            })
        .orElse(0L);
  }

  private static final Pattern CONTENT_RANGE_TOTAL = Pattern.compile("bytes\\s+\\d+-\\d+/(\\d+)");

  // ------------------------------------------------------------------ stat

  private Info info(String url) throws IOException {
    Info cached = infos.get(url);
    if (cached != null) return cached;
    Info info = url.endsWith("/") ? directoryInfo(url) : fileInfo(url);
    infos.put(url, info);
    return info;
  }

  private Info directoryInfo(String url) throws IOException {
    HttpResponse<InputStream> r = send(request(url).GET().build());
    try (InputStream in = r.body()) {
      return r.statusCode() == 200
          ? new Info(true, true, 0, modified(r.headers()), null)
          : Info.MISSING;
    }
  }

  private Info fileInfo(String url) throws IOException {
    boolean useHead = !"NO".equalsIgnoreCase(option("CPL_VSIL_CURL_USE_HEAD", "YES"));
    if (useHead) {
      HttpResponse<InputStream> head =
          send(request(url).method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
      head.body().close();
      int status = head.statusCode();
      if (status == 404 || status == 410) return Info.MISSING;
      if (status == 200) {
        boolean chunked = head.headers().firstValue("Transfer-Encoding").isPresent();
        Optional<String> length = head.headers().firstValue("Content-Length");
        if (!chunked && length.isPresent()) {
          return new Info(
              true, false, Long.parseLong(length.get().strip()), modified(head.headers()), null);
        }
      }
      // HEAD not allowed, or no usable size: ask for the first chunk
    }
    return probeWithGet(url);
  }

  /**
   * A ranged GET of the first chunk gives the size (Content-Range), or the whole file if ranges are
   * ignored.
   */
  private Info probeWithGet(String url) throws IOException {
    long chunk = number("CPL_VSIL_CURL_CHUNK_SIZE", 16384);
    HttpResponse<InputStream> r =
        send(request(url).header("Range", "bytes=0-" + (chunk - 1)).GET().build());
    try (InputStream in = r.body()) {
      int status = r.statusCode();
      long mod = modified(r.headers());
      if (status == 206) {
        Matcher m = CONTENT_RANGE_TOTAL.matcher(r.headers().firstValue("Content-Range").orElse(""));
        if (m.find()) return new Info(true, false, Long.parseLong(m.group(1)), mod, null);
        throw new IOException("Server answered a range request without a total size: " + url);
      }
      if (status == 200) {
        Optional<String> length = r.headers().firstValue("Content-Length");
        boolean chunked = r.headers().firstValue("Transfer-Encoding").isPresent();
        if (length.isPresent() && !chunked) {
          long size = Long.parseLong(length.get().strip());
          // the server ignores Range: remember the body if it is small enough, else read it per
          // request
          byte[] whole = size <= chunk * 4 ? in.readAllBytes() : null;
          return new Info(true, false, size, mod, whole);
        }
        byte[] whole = in.readNBytes(MAX_WHOLE_BODY);
        return new Info(true, false, whole.length, mod, whole);
      }
      if (status == 416) return new Info(true, false, 0, mod, null); // empty file
      if (status == 404 || status == 410) return Info.MISSING;
      throw new IOException("HTTP " + status + " for " + url);
    }
  }

  @Override
  public Optional<VsiStat> stat(Vsi vsi, String path) throws IOException {
    String url = url(path);
    if (url.isEmpty() || url.equals("/")) return Optional.of(VsiStat.dir());
    Info i = info(url);
    if (!i.exists()) return Optional.empty();
    return Optional.of(i.directory() ? VsiStat.dir() : VsiStat.file(i.size(), i.modified()));
  }

  // ------------------------------------------------------------------ read

  @Override
  public VsiFile open(Vsi vsi, String path, VsiMode mode, Map<String, String> options)
      throws IOException {
    if (mode != VsiMode.READ) throw new IOException("/vsicurl/ is read-only");
    String url = url(path);
    Info i = info(url);
    if (!i.exists()) throw new NoSuchFileException(path);
    if (i.directory()) throw new IOException("Is a directory: " + path);
    return new CurlFile(url, i);
  }

  /** The bytes {@code [from, to]} of a URL, as the server delivers them. */
  private byte[] fetch(String url, Info info, long from, long to) throws IOException {
    if (info.whole() != null) {
      int end = (int) Math.min(info.whole().length, to + 1);
      return from >= end
          ? new byte[0]
          : java.util.Arrays.copyOfRange(info.whole(), (int) from, end);
    }
    HttpResponse<InputStream> r =
        send(request(url).header("Range", "bytes=" + from + "-" + to).GET().build());
    try (InputStream in = r.body()) {
      int status = r.statusCode();
      if (status == 206) return in.readAllBytes();
      if (status == 416) return new byte[0];
      if (status == 200) {
        // range ignored: skip to the start of what was asked for
        in.skipNBytes(from);
        return in.readNBytes((int) (to - from + 1));
      }
      throw new IOException("HTTP " + status + " reading " + url);
    }
  }

  private synchronized byte[] chunk(String url, Info info, long index, long chunkSize)
      throws IOException {
    String key = url + '#' + index;
    byte[] c = chunks.get(key);
    if (c != null) return c;
    c = fetch(url, info, index * chunkSize, Math.min(info.size() - 1, (index + 1) * chunkSize - 1));
    chunks.put(key, c);
    cachedBytes += c.length;
    long max = number("CPL_VSIL_CURL_CACHE_SIZE", 16_000_000);
    var it = chunks.entrySet().iterator();
    while (cachedBytes > max && it.hasNext()) {
      var e = it.next();
      if (e.getValue() == c) break;
      cachedBytes -= e.getValue().length;
      it.remove();
    }
    return c;
  }

  private final class CurlFile extends ReadOnlyVsiFile {
    private final String url;
    private final Info info;
    private final long chunkSize = Math.max(1, number("CPL_VSIL_CURL_CHUNK_SIZE", 16384));

    CurlFile(String url, Info info) {
      this.url = url;
      this.info = info;
    }

    @Override
    protected int readAt(ByteBuffer dst, long pos) throws IOException {
      if (!dst.hasRemaining()) return 0;
      if (pos >= info.size()) return -1;
      int total = 0;
      long p = pos;
      while (dst.hasRemaining() && p < info.size()) {
        long index = p / chunkSize;
        byte[] c = chunk(url, info, index, chunkSize);
        int off = (int) (p - index * chunkSize);
        if (off >= c.length) break;
        int n = Math.min(dst.remaining(), c.length - off);
        dst.put(c, off, n);
        total += n;
        p += n;
      }
      return total == 0 ? -1 : total;
    }

    @Override
    public long size() {
      return info.size();
    }
  }

  // ------------------------------------------------------------------ directories

  @Override
  public List<String> list(Vsi vsi, String dir) throws IOException {
    String url = url(dir);
    if (!url.endsWith("/")) url += "/";
    HttpResponse<InputStream> r = send(request(url).GET().build());
    String html;
    try (InputStream in = r.body()) {
      if (r.statusCode() == 404) throw new NoSuchFileException(dir);
      if (r.statusCode() != 200)
        throw new IOException("HTTP " + r.statusCode() + " listing " + url);
      html = new String(in.readNBytes(MAX_WHOLE_BODY), java.nio.charset.StandardCharsets.UTF_8);
    }
    List<String> names = new ArrayList<>();
    Matcher m = HREF.matcher(html);
    while (m.find()) {
      String href = m.group(1);
      if (href.isEmpty()
          || href.startsWith("?")
          || href.startsWith("/")
          || href.startsWith("#")
          || href.contains("://")) continue;
      if (href.equals("./") || href.equals("../")) continue;
      names.add(href.endsWith("/") ? href.substring(0, href.length() - 1) : href);
    }
    return names;
  }
}
