package dev.jdal.http;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.jdal.testkit.Port;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpTest {

  HttpServer server;
  String base;
  final Map<String, String> config = new HashMap<>();
  final List<String> authorization = Collections.synchronizedList(new ArrayList<>());
  final AtomicInteger failures = new AtomicInteger();
  int failWith = 503;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        x -> {
          authorization.add(String.valueOf(x.getRequestHeaders().getFirst("Authorization")));
          byte[] body;
          int status;
          if (x.getRequestURI().getPath().equals("/flaky") && failures.getAndDecrement() > 0) {
            status = failWith;
            body = "busy".getBytes(StandardCharsets.UTF_8);
          } else {
            status = 200;
            body =
                ("ua="
                        + x.getRequestHeaders().getFirst("User-Agent")
                        + ",x="
                        + x.getRequestHeaders().getFirst("X-Custom"))
                    .getBytes(StandardCharsets.UTF_8);
          }
          x.sendResponseHeaders(status, body.length);
          x.getResponseBody().write(body);
          x.close();
        });
    server.start();
    base = "http://localhost:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void userAgentAndHeadersFromOptions() throws IOException {
    config.put("GDAL_HTTP_USERAGENT", "me");
    config.put("GDAL_HTTP_HEADERS", "X-Custom: 42\nIgnored line");
    assertEquals("ua=me,x=42", new Http(config::get).get(base + "/x").text());
    assertEquals("ua=jdal,x=null", new Http(k -> null).get(base + "/x").text());
  }

  @Test
  void basicAndBearerAuthentication() throws IOException {
    config.put("GDAL_HTTP_USERPWD", "user:pw");
    new Http(config::get).get(base + "/x");
    assertEquals("Basic dXNlcjpwdw==", authorization.getLast());
    config.put("GDAL_HTTP_BEARER", "token");
    new Http(config::get).get(base + "/x");
    assertEquals("Bearer token", authorization.getLast());
  }

  @Port("autotest/gcore/vsicurl.py::test_vsicurl_test_retry")
  @Test
  void retriesOnServerErrors() throws IOException {
    config.put("GDAL_HTTP_MAX_RETRY", "2");
    config.put("GDAL_HTTP_RETRY_DELAY", "0");
    failures.set(2);
    Http.Response r = new Http(config::get).get(base + "/flaky");
    assertEquals(200, r.status());
    assertEquals(3, authorization.size());

    failures.set(5); // more failures than retries: the last answer is returned
    authorization.clear();
    assertEquals(503, new Http(config::get).get(base + "/flaky").status());
    assertEquals(3, authorization.size());
  }

  @Port({
    "autotest/gcore/vsicurl.py::test_vsicurl_retry_codes_ALL",
    "autotest/gcore/vsicurl.py::test_vsicurl_retry_codes_enumerated",
    "autotest/gcore/vsicurl.py::test_vsicurl_retry_codes_no_match"
  })
  @Test
  void retryCodes() throws IOException {
    config.put("GDAL_HTTP_MAX_RETRY", "1");
    config.put("GDAL_HTTP_RETRY_DELAY", "0");
    failWith = 400;
    failures.set(1);
    assertEquals(
        400, new Http(config::get).get(base + "/flaky").status()); // 400 is not retried by default
    config.put("GDAL_HTTP_RETRY_CODES", "ALL");
    failures.set(1);
    assertEquals(200, new Http(config::get).get(base + "/flaky").status());
    config.put("GDAL_HTTP_RETRY_CODES", "404,400");
    failures.set(1);
    assertEquals(200, new Http(config::get).get(base + "/flaky").status());
    config.put("GDAL_HTTP_RETRY_CODES", "500");
    failures.set(1);
    assertEquals(400, new Http(config::get).get(base + "/flaky").status());
  }
}
