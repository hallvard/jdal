package dev.jdal.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * An HTTP client configured like GDAL's: the settings come from options with the same names, read
 * from system properties, the environment and a {@code .env} file in the current directory (see
 * {@code dev.jdal.config.Config}), or any other lookup, for tests.
 *
 * <ul>
 *   <li>{@code GDAL_HTTP_HEADERS}: extra headers, one {@code Name: value} per line
 *   <li>{@code GDAL_HTTP_USERAGENT}, {@code GDAL_HTTP_TIMEOUT} (seconds, 100), {@code
 *       GDAL_HTTP_CONNECTTIMEOUT} (30)
 *   <li>{@code GDAL_HTTP_USERPWD} ({@code user:password}, basic authentication), {@code
 *       GDAL_HTTP_BEARER}
 *   <li>{@code GDAL_HTTP_MAX_RETRY} (0), {@code GDAL_HTTP_RETRY_DELAY} (seconds, 30), {@code
 *       GDAL_HTTP_RETRY_CODES} (429,500,502,503,504 or ALL)
 * </ul>
 */
public final class Http {

  /** A response with its body read. */
  public record Response(int status, HttpHeaders headers, byte[] body, URI uri) {
    public boolean ok() {
      return status >= 200 && status < 300;
    }

    public String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    public String header(String name) {
      return headers.firstValue(name).orElse(null);
    }
  }

  private static final class Holder {
    static final Http GLOBAL = new Http(Http::systemConfig);
  }

  private final Function<String, String> config;
  private final HttpClient client;

  public Http(Function<String, String> config) {
    this.config = config;
    this.client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(number("GDAL_HTTP_CONNECTTIMEOUT", 30)))
            .build();
  }

  public static Http global() {
    return Holder.GLOBAL;
  }

  private static String systemConfig(String key) {
    return dev.jdal.config.Config.global().get(key);
  }

  public String option(String key, String fallback) {
    String v = config.apply(key);
    return v == null ? fallback : v;
  }

  public long number(String key, long fallback) {
    try {
      return Long.parseLong(option(key, String.valueOf(fallback)).strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private double seconds(String key, double fallback) {
    try {
      return Double.parseDouble(option(key, String.valueOf(fallback)).strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /**
   * A request for {@code url} with the user agent, timeout, authentication and extra headers set.
   */
  public HttpRequest.Builder request(String url) throws IOException {
    HttpRequest.Builder b;
    try {
      b =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(number("GDAL_HTTP_TIMEOUT", 100)));
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid URL " + url);
    }
    b.header("User-Agent", option("GDAL_HTTP_USERAGENT", "jdal"));
    String userPwd = config.apply("GDAL_HTTP_USERPWD");
    String bearer = config.apply("GDAL_HTTP_BEARER");
    if (bearer != null) {
      b.header("Authorization", "Bearer " + bearer);
    } else if (userPwd != null) {
      b.header(
          "Authorization",
          "Basic " + Base64.getEncoder().encodeToString(userPwd.getBytes(StandardCharsets.UTF_8)));
    }
    String headers = config.apply("GDAL_HTTP_HEADERS");
    if (headers != null) {
      for (String line : headers.split("\n")) {
        int colon = line.indexOf(':');
        if (colon > 0)
          b.header(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
      }
    }
    return b;
  }

  private boolean retryable(int status) {
    String codes = option("GDAL_HTTP_RETRY_CODES", "429,500,502,503,504").strip();
    if (codes.equalsIgnoreCase("ALL")) return status >= 400;
    Set<Integer> set = new HashSet<>();
    for (String c : codes.split(",")) {
      try {
        set.add(Integer.parseInt(c.strip()));
      } catch (NumberFormatException e) {
        // ignore bad entries
      }
    }
    return set.contains(status);
  }

  /** Sends a request, retrying on the configured status codes; the body is a stream to close. */
  public HttpResponse<InputStream> send(HttpRequest request) throws IOException {
    long maxRetry = number("GDAL_HTTP_MAX_RETRY", 0);
    double delay = seconds("GDAL_HTTP_RETRY_DELAY", 30);
    for (long attempt = 0; ; attempt++) {
      HttpResponse<InputStream> r;
      try {
        r = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while requesting " + request.uri());
      } catch (IOException e) {
        if (attempt >= maxRetry) throw e;
        pause(delay);
        continue;
      }
      if (attempt < maxRetry && retryable(r.statusCode())) {
        r.body().close();
        pause(delay);
        continue;
      }
      return r;
    }
  }

  private static void pause(double seconds) throws IOException {
    try {
      Thread.sleep((long) (seconds * 1000));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting to retry");
    }
  }

  /** GET with extra headers; the whole body is read. */
  public Response get(String url, Map<String, String> headers) throws IOException {
    HttpRequest.Builder b = request(url).GET();
    headers.forEach(b::header);
    HttpResponse<InputStream> r = send(b.build());
    try (InputStream in = r.body()) {
      return new Response(r.statusCode(), r.headers(), in.readAllBytes(), r.uri());
    }
  }

  public Response get(String url) throws IOException {
    return get(url, Map.of());
  }
}
