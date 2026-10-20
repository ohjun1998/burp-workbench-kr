package kr.featuremapper;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

/** Transport readiness and exact request correlation must remain separate observations. */
final class MapperHardeningTest {
  static void check(boolean value, String message) { MapperTest.check(value, message); }

  static void run() throws Exception {
    correlation();
    transport();
  }

  private static void correlation() throws Exception {
    long t = System.currentTimeMillis();
    try (Mapper mapper = new Mapper()) {
      var first = MapperTest.input(t, "POST", "https://lab.test/api/shared", "one", "{}", "A");
      var second = MapperTest.input(t, "POST", first.url(), "two", "{}", "A");
      // Same millisecond, action, URL and method: the body still identifies distinct requests.
      mapper.browser(MapperTest.event(t, "POST", first.url(), Signals.digest(first.requestBody()), "action", "1", "저장"));
      mapper.browser(MapperTest.event(t, "POST", second.url(), Signals.digest(second.requestBody()), "action", "1", "저장"));
      var a = mapper.accept(first);
      var b = mapper.accept(second);
      check(!a.browserAction.isEmpty() && !b.browserAction.isEmpty(), "same-time browser events preserve distinct request body hashes");
      check(mapper.browserMappedPackets() == 2, "browser mapped count excludes unrelated HTTP packets");
      check(a.reasons.stream().anyMatch(s -> s.contains("본문 SHA-256 일치")), "mapping evidence records exact method URL time and body-hash agreement");

      String shared = "https://lab.test/api/method";
      mapper.browser(MapperTest.event(t, "GET", shared, "", "methods", "1", "확인"));
      mapper.browser(MapperTest.event(t, "POST", shared, "", "methods", "1", "확인"));
      var get = mapper.accept(MapperTest.input(t, "GET", shared, "", "{}", "A"));
      var post = mapper.accept(MapperTest.input(t, "POST", shared, "", "{}", "A"));
      check(!get.browserAction.isEmpty() && !post.browserAction.isEmpty(), "same-time browser events preserve distinct HTTP methods");
      check(get.reasons.stream().anyMatch(s -> s.contains("본문 해시 미확인")), "hashless browser evidence stays explicitly unverified");

      var no = mapper.accept(MapperTest.input(t, "PUT", "https://lab.test/api/negative?x=1", "target", "{}", "A"));
      mapper.browser(MapperTest.event(t, "POST", no.url, no.bodyHash, "wrong-method", "1", "오연결"));
      mapper.browser(MapperTest.event(t, "PUT", no.url + "0", no.bodyHash, "wrong-url", "1", "오연결"));
      mapper.browser(MapperTest.event(t, "PUT", no.url, Signals.digest("different"), "wrong-body", "1", "오연결"));
      mapper.browser(MapperTest.event(t + Mapper.BROWSER_WINDOW, "PUT", no.url, no.bodyHash, "wrong-time", "1", "오연결"));
      check(no.browserAction.isEmpty(), "different method URL body and time cannot become a click attribution");
      try {
        mapper.browser(MapperTest.event(t, "PUT", no.url, "not-a-sha256", "bad-hash", "1", "오연결"));
        throw new AssertionError("invalid hash accepted");
      } catch (IllegalArgumentException expected) { check(true, "malformed body hash is rejected before indexing"); }

      mapper.accept(first);
      check(a.browserAction.isEmpty() && b.browserAction.isEmpty() && mapper.browserMappedPackets() == 2, "ambiguous request retracts both mapping evidence and mapped count");
      check(a.reasons.stream().noneMatch(s -> s.startsWith("브라우저 요청 대조:")), "retracted mapping removes stale exact-match evidence");
      Path project = Files.createTempFile("mapper-count-", ".fmkr");
      try {
        ProjectIO.save(mapper, project);
        try (Mapper restored = ProjectIO.load(project)) {
          check(restored.browserMappedPackets() == mapper.browserMappedPackets(), "project restore reconstructs browser linkage counts from stored evidence");
        }
      } finally { Files.deleteIfExists(project); }
    }
  }

  private static void transport() throws Exception {
    HttpClient http = HttpClient.newHttpClient();
    try (BrowserBridge bridge = new BrowserBridge(event -> {})) {
      check(bridge.snapshot().running() && bridge.snapshot().lastAuthenticated() == 0,
          "running bridge never claims an authenticated helper connection");
      String base = bridge.baseUrl();
      var refused = http.send(HttpRequest.newBuilder(URI.create(base + "/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
      check(refused.statusCode() == 401 && bridge.snapshot().lastAuthenticated() == 0 && bridge.snapshot().rejectedRequests() == 1,
          "failed authentication increments rejection without marking connected");
      var suffix = http.send(HttpRequest.newBuilder(URI.create(base + "/health-other")).header("X-Feature-Mapper-Token", bridge.token).GET().build(), HttpResponse.BodyHandlers.ofString());
      check(suffix.statusCode() == 404 && bridge.snapshot().lastAuthenticated() == 0, "health requires its exact endpoint path");
      var health = http.send(HttpRequest.newBuilder(URI.create(base + "/health")).header("X-Feature-Mapper-Token", bridge.token).GET().build(), HttpResponse.BodyHandlers.ofString());
      check(health.statusCode() == 200 && bridge.snapshot().lastAuthenticated() > 0 && bridge.snapshot().receivedEvents() == 0,
          "pairing success is separate from receiving any click request");
      var body = MapperTest.event(System.currentTimeMillis(), "GET", "https://lab.test/api/posts", "", "click", "1", "조회");
      var event = http.send(HttpRequest.newBuilder(URI.create(base + "/event")).header("X-Feature-Mapper-Token", bridge.token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JsonWrite.text(body))).build(), HttpResponse.BodyHandlers.ofString());
      check(event.statusCode() == 200 && bridge.snapshot().receivedEvents() == 1 && bridge.snapshot().lastEvent() > 0,
          "transport counts received metadata even when the sink does not collect it");
      String current = bridge.snapshot().description(System.currentTimeMillis());
      check(current.contains("최근 인증 통신 확인") && !current.contains(bridge.token) && !current.contains("lab.test"), "connection diagnostics contain neither pairing token nor target request data");
      check(bridge.snapshot().description(System.currentTimeMillis() + 100_000).contains("연결 재확인"), "idle authenticated transport does not claim a live connection forever");
      bridge.close();
      check(!bridge.snapshot().running(), "unloaded bridge reports a closed transport");
    }
  }
}
