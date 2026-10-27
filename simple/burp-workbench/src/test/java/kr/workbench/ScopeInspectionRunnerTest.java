package kr.workbench;

import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Actual loopback requests through the packaged inspection coordinator; no native Burp or external hosts. */
public final class ScopeInspectionRunnerTest extends WorkbenchTest {
  static void await(BooleanSupplier condition, String note) throws Exception {
    for (int i = 0; i < 1800; i++) { if (condition.getAsBoolean()) return; Thread.sleep(10); }
    throw new AssertionError("통합 점검 대기 시간 초과: " + note);
  }
  static boolean terminal(ScopeInspectionRunner runner) {
    return Set.of(ScopeInspection.State.COMPLETED, ScopeInspection.State.STOPPED, ScopeInspection.State.FAILED, ScopeInspection.State.CLOSED).contains(runner.snapshot().state());
  }
  static ScopeInspection.Options options(ScopeInspectionFixture fixture, String path, int budget, int depth,
                                          boolean browser, List<ScopeInspection.Profile> profiles) {
    return new ScopeInspection.Options(fixture.origin + path, budget, depth, 0, 3000, 1024 * 1024, 3, browser, profiles.size() > 1, profiles);
  }
  static List<ScopeInspection.Profile> anonymous() { return List.of(new ScopeInspection.Profile("anonymous", "비로그인", Map.of(), null)); }
  static void reply(HttpExchange exchange, int code, String type, String body) throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
  }

  public static void main(String[] args) throws Exception {
    check(ScopeInspectionRunner.normalized("https://example.test/app/../secret") == null, "literal traversal is excluded before a request");
    check(ScopeInspectionRunner.normalized("https://example.test/app/%252fsecret") == null, "double-encoded path ambiguity is excluded");
    check(ScopeInspectionRunner.normalized("https://user:password@example.test/app/") == null, "userinfo URL cannot carry credentials into discovery");
    check(ScopeInspectionRunner.normalized("https://example.test/app/#/view") == null, "SPA hash navigation is reserved for the browser adapter");
    check(!ScopeInspectionRunner.readOnlyUrl("https://example.test/app/deleteUser/10"), "destructive-looking GET routes stay manual");
    check(!ScopeInspectionRunner.readOnlyUrl("https://example.test/app/actuator/heapdump"), "large diagnostic dumps stay manual");
    check(!ScopeInspectionRunner.readOnlyUrl("https://example.test/app/file.sql"), "database archive downloads stay manual");

    try (var fixture = new ScopeInspectionFixture()) {
      var allowed = (java.util.function.Predicate<String>) url -> url.equals(fixture.origin + "/app") || url.startsWith(fixture.origin + "/app/");
      var updates = new CopyOnWriteArrayList<ScopeInspection.Snapshot>();
      try (var runner = new ScopeInspectionRunner(fixture.api, fixture.options(true), allowed, updates::add)) {
        check(fixture.visits.isEmpty() && runner.snapshot().state() == ScopeInspection.State.READY, "construction and result preparation send no HTTP");
        runner.start(); await(() -> terminal(runner), "multi-account inspection");
        var result = runner.snapshot();
        check(result.state() == ScopeInspection.State.COMPLETED, "bounded fixture queue completes");
        check(result.sent() == fixture.visits.size() && result.sent() <= 150, "counter includes actual identity, reference and target HTTP across accounts");
        check(result.failed() == 0 && result.pending() == 0, "completed fixture has no failed or pending requests");
        check(fixture.visits.stream().allMatch(v -> Set.of("GET", "HEAD").contains(v.method())), "all automatic HTTP uses GET or HEAD");
        check(fixture.visits.stream().noneMatch(v -> v.path().startsWith("/outside") || v.path().contains("logout") || v.path().endsWith("write-only")), "scope, logout and write-only declarations are never fetched");
        check(fixture.visits.stream().anyMatch(v -> v.path().equals("/app/api/undocumented")), "observed JS introduces its resolved GET API into the queue");
        check(fixture.visits.stream().noneMatch(v -> v.path().contains("{id}")), "unresolved API path variables are never invented");
        check(result.findings().stream().anyMatch(f -> f.category().equals("DATABASE") && f.title().contains("PostgreSQL")), "DB product clues are aggregated as findings");
        check(result.findings().stream().anyMatch(f -> f.category().equals("ACCESS") && f.state().contains("수동")), "account response differences are review candidates rather than a vulnerability verdict");
        check(result.findings().stream().anyMatch(f -> f.category().equals("SECRET")), "actual fetched source is passed to secret analysis");
        check(!result.toString().contains(ScopeInspectionFixture.API_SECRET) && !result.toString().contains("DB_PRIVATE_VALUE")
            && !result.toString().contains(ScopeInspectionFixture.SESSION), "public run snapshots contain no raw secrets, DB password or session cookie");
        var original = result.pages().stream().filter(page -> page.url().endsWith("/app/private") && page.account().equals("Alice") && page.status() == 200).findFirst().orElseThrow();
        check(runner.original(original.original().id()) == original.original(), "finding and page IDs resolve to the retained immutable original");
        check(new String(original.original().requestPrefix(10000), StandardCharsets.UTF_8).contains(ScopeInspectionFixture.SESSION), "private original retains the exact selected account request");
        check(original.original().packet().request().url().equals(original.url()), "stored original can be reconstructed with its exact service and URL");
        check(original.original().packet().response().bodyToString().contains("alice"), "stored response can be reconstructed without a preview surrogate");
        check(result.pages().stream().anyMatch(page -> page.classification().equals("SOFT404")), "common 200 fallback pages remain explicitly classified");
        check(result.pages().stream().filter(page -> Set.of("DENIED", "REDIRECT").contains(page.classification()))
            .allMatch(page -> !page.complete() && page.note().contains("대상 내용 미확인")), "blocked target responses retain static observations without claiming target content coverage");
        check(updates.stream().anyMatch(update -> update.state() == ScopeInspection.State.RUNNING), "progress is observable before completion");
      }
      fixture.visits.clear();
      try (var runner = new ScopeInspectionRunner(fixture.api, options(fixture, "/app/", 2, 2, false, anonymous()), allowed, value -> {})) {
        runner.start(); await(() -> terminal(runner), "two-request budget");
        check(runner.snapshot().state() == ScopeInspection.State.STOPPED && runner.snapshot().sent() == 2, "missing-page reference requests consume the global budget");
        check(fixture.visits.size() == 2 && fixture.visits.stream().allMatch(v -> v.path().contains("wb-missing-")), "an exhausted reference budget does not send the target request");
        check(runner.snapshot().pending() > 0 && runner.snapshot().findings().stream().anyMatch(f -> f.category().equals("COVERAGE")), "budget exhaustion preserves pending work and a coverage explanation");
      }
      fixture.visits.clear();
      try (var runner = new ScopeInspectionRunner(fixture.api, options(fixture, "/app/", 20, 0, false, anonymous()), url -> false, value -> {})) {
        rejects(runner::start, "an excluded starting scope is rejected without network");
        check(fixture.visits.isEmpty(), "scope rejection sends zero HTTP");
      }

      fixture.server.createContext("/app/inside-redirect", exchange -> {
        exchange.getResponseHeaders().set("Location", "/app/api/documented"); reply(exchange, 302, "text/plain", "redirect");
      });
      try (var runner = new ScopeInspectionRunner(fixture.api, options(fixture, "/app/inside-redirect", 20, 0, false, anonymous()), allowed, value -> {})) {
        runner.start(); await(() -> terminal(runner), "inside redirect");
        var page = runner.snapshot().pages().stream().filter(p -> p.url().endsWith("/app/api/documented") && p.classification().equals("JSON")).findFirst().orElseThrow();
        check(page.original().request().url().equals(page.url()), "redirect destination analysis links the final request, not the initial URL");
        check(runner.snapshot().pages().stream().anyMatch(p -> p.url().endsWith("inside-redirect") && p.status() == 302), "each redirect hop retains its own wire original");
      }
      try (var runner = new ScopeInspectionRunner(fixture.api, options(fixture, "/app/loop", 20, 0, false, anonymous()), allowed, value -> {})) {
        runner.start(); await(() -> terminal(runner), "redirect loop");
        check(runner.snapshot().findings().stream().anyMatch(f -> f.detail().contains("리디렉션 순환")), "redirect loops are reported without infinite requests");
      }

      AtomicInteger logGets = new AtomicInteger(), logHeads = new AtomicInteger();
      fixture.server.createContext("/app/logs", exchange -> {
        if (exchange.getRequestMethod().equals("HEAD")) {
          logHeads.incrementAndGet(); exchange.getResponseHeaders().set("Content-Length", "9000000");
          exchange.getResponseHeaders().set("Content-Type", "text/plain"); exchange.sendResponseHeaders(200, -1); exchange.close();
        } else { logGets.incrementAndGet(); reply(exchange, 200, "text/plain", "must not download"); }
      });
      try (var runner = new ScopeInspectionRunner(fixture.api, options(fixture, "/app/logs", 20, 0, false, anonymous()), allowed, value -> {})) {
        runner.start(); await(() -> terminal(runner), "large log preflight");
        check(logHeads.get() == 1 && logGets.get() == 0, "large declared log receives HEAD metadata but no automatic GET download");
        check(runner.snapshot().pages().stream().anyMatch(p -> p.classification().equals("HEAD_PREFLIGHT")), "large-file metadata has its own retained original");
      }
      fixture.server.createContext("/app/huge", exchange -> reply(exchange, 200, "text/plain", "X".repeat(4096)));
      var small = new ScopeInspection.Options(fixture.origin + "/app/huge", 20, 0, 0, 3000, 1024, 2, false, false, anonymous());
      try (var runner = new ScopeInspectionRunner(fixture.api, small, allowed, value -> {})) {
        runner.start(); await(() -> terminal(runner), "body analysis cap");
        var page = runner.snapshot().pages().stream().filter(p -> p.url().endsWith("/app/huge")).findFirst().orElseThrow();
        check(!page.complete() && page.classification().equals("LIMIT"), "an oversized received body is explicitly uninspected");
        check(page.original().responseBodyLength() == 4096 && page.original().response().body().length() == 4096, "analysis truncation does not truncate the retained wire original");
        check(page.note().contains("다운로드 상한은 별개"), "transport download and analysis limits are not conflated");
      }
      var zipped = new java.io.ByteArrayOutputStream();
      try (var gzip = new java.util.zip.GZIPOutputStream(zipped)) { gzip.write("Z".repeat(4096).getBytes(StandardCharsets.UTF_8)); }
      byte[] compressed = zipped.toByteArray();
      fixture.server.createContext("/app/gzip-huge", exchange -> {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        exchange.sendResponseHeaders(200, compressed.length); exchange.getResponseBody().write(compressed); exchange.close();
      });
      var compressedOptions = new ScopeInspection.Options(fixture.origin + "/app/gzip-huge", 20, 0, 0, 3000, 1024, 2, false, false, anonymous());
      try (var runner = new ScopeInspectionRunner(fixture.api, compressedOptions, allowed, value -> {})) {
        runner.start(); await(() -> terminal(runner), "decoded body analysis cap");
        var page = runner.snapshot().pages().stream().filter(p -> p.url().endsWith("/app/gzip-huge")).findFirst().orElseThrow();
        check(!page.complete() && page.classification().equals("LIMIT") && page.note().contains("압축 해제 한도"), "configured body bound also limits decompressed bytes before static analysis");
        byte[] wire = page.original().responsePrefix(10000);
        check(page.original().responseBodyLength() == compressed.length
            && Arrays.equals(compressed, Arrays.copyOfRange(wire, wire.length - compressed.length, wire.length)), "decompression limits preserve the exact compressed wire original");
      }

      fixture.rateLimited.set(true); fixture.visits.clear();
      try (var runner = new ScopeInspectionRunner(fixture.api, options(fixture, "/app/rate", 25, 0, false, anonymous()), allowed, value -> {})) {
        runner.start(); await(() -> runner.snapshot().state() == ScopeInspection.State.PAUSED, "429 pause");
        check(runner.snapshot().pages().stream().anyMatch(p -> p.status() == 429 && !p.complete() && p.note().contains("대상 내용 미확인")), "rate limiting is explicit incomplete target coverage");
        int count = fixture.visits.size(); Thread.sleep(80);
        check(fixture.visits.size() == count && runner.snapshot().pending() > 0, "429 pause gates later requests and retains the limited candidate");
        fixture.rateLimited.set(false); runner.resume(); await(() -> terminal(runner), "429 explicit resume");
        check(runner.snapshot().pages().stream().anyMatch(p -> p.url().endsWith("/app/rate") && p.status() == 429)
            && runner.snapshot().pages().stream().anyMatch(p -> p.url().endsWith("/app/rate") && p.status() == 200), "resume keeps the limited attempt and the subsequent successful original");
      }

      var identity = new CookieSessionProbe.Config(fixture.origin + "/app/api/me", "$.id", "alice");
      var expired = new ScopeInspection.Profile("alice", "Alice", Map.of("Cookie", "SID=EXPIRED"), identity);
      var refreshed = new ScopeInspection.Profile("alice", "Alice", Map.of("Cookie", ScopeInspectionFixture.SESSION), identity);
      try (var runner = new ScopeInspectionRunner(fixture.api, options(fixture, "/app/private", 25, 0, false, List.of(expired)), allowed, value -> {})) {
        runner.start(); await(() -> runner.snapshot().state() == ScopeInspection.State.PAUSED, "expired identity");
        check(runner.snapshot().pages().stream().noneMatch(p -> p.url().endsWith("/app/private")), "expired identity blocks the discovery target");
        runner.resume(List.of(refreshed)); await(() -> terminal(runner), "same-account refreshed identity");
        check(runner.snapshot().pages().stream().anyMatch(p -> p.profileRevision().startsWith("r1:"))
            && runner.snapshot().pages().stream().anyMatch(p -> p.profileRevision().startsWith("r2:")), "refresh preserves old and new authentication revisions separately");
        check(runner.snapshot().pages().stream().anyMatch(p -> p.url().endsWith("/app/private") && p.status() == 200), "same declared identity can resume with refreshed credentials");
      }

      AtomicBoolean liveScope = new AtomicBoolean(true);
      fixture.rateLimited.set(true); fixture.visits.clear();
      try (var runner = new ScopeInspectionRunner(fixture.api, options(fixture, "/app/rate", 25, 0, false, anonymous()), url -> liveScope.get() && allowed.test(url), value -> {})) {
        runner.start(); await(() -> runner.snapshot().state() == ScopeInspection.State.PAUSED, "pause before scope revocation");
        int count = fixture.visits.size(); liveScope.set(false);
        await(() -> terminal(runner), "scope revocation halts paused worker");
        check(fixture.visits.size() == count && runner.snapshot().message().contains("SCOPE"), "changing the frozen scope stops future HTTP and preserves an explicit reason");
      }
      fixture.rateLimited.set(false);

      var browserOptions = options(fixture, "/app/", 30, 0, true, anonymous());
      try (var runner = new ScopeInspectionRunner(fixture.api, browserOptions, allowed, value -> {})) {
        runner.start();
        runner.offerBrowser(Map.of("runId", "other-run", "kind", "menu", "url", fixture.origin + "/app/private", "routeId", "wrong"));
        runner.offerBrowser(Map.of("runId", runner.id(), "kind", "menu", "url", fixture.origin + "/app/", "routeId", "route-A", "label", "First SPA menu"));
        runner.offerBrowser(Map.of("runId", runner.id(), "kind", "menu", "url", fixture.origin + "/app/", "routeId", "route-B", "label", "Second SPA menu"));
        runner.offerBrowser(Map.of("runId", runner.id(), "kind", "api", "url", fixture.origin + "/app/api/me", "method", "GET", "routeId", "api-me", "pageRouteId", "route-A"));
        runner.offerBrowser(Map.of("runId", runner.id(), "kind", "api", "url", fixture.origin + "/app/api/me", "method", "GET", "routeId", "api-me", "pageRouteId", "route-B"));
        runner.offerBrowser(Map.of("runId", runner.id(), "kind", "api", "url", fixture.origin + "/app/api/documented", "method", "GET", "requiresQuery", true, "routeId", "query-required"));
        runner.offerBrowser(Map.of("runId", runner.id(), "kind", "api", "url", fixture.origin + "/app/api/write-only", "method", "POST", "routeId", "write"));
        runner.offerBrowser(Map.of("runId", runner.id(), "kind", "done", "state", "partial"));
        await(() -> terminal(runner), "browser route events");
        var snapshot = runner.snapshot();
        check(snapshot.findings().stream().filter(f -> f.account().equals("browser-session") && f.category().equals("MENU")).count() == 2, "SPA hash routes remain distinct metadata without leaking full route values");
        check(snapshot.findings().stream().filter(f -> f.account().equals("browser-session") && f.category().equals("API") && f.detail().contains("페이지 라우트 지문 route-")).count() == 2, "one API retains separate observed contexts for two SPA routes without claiming JS causality");
        check(snapshot.findings().stream().anyMatch(f -> f.title().equals("브라우저 탐색 미완료")) && snapshot.partial() > 0, "browser partial completion remains explicit coverage rather than full completion");
        check(snapshot.candidates().stream().anyMatch(c -> c.url().endsWith("/app/api/documented") && !c.executable()), "browser query values that were removed are never invented for HTTP replay");
        check(snapshot.candidates().stream().anyMatch(c -> c.method().equals("POST") && !c.executable()), "browser writes are inventoried without automatic transmission");
        check(snapshot.findings().stream().noneMatch(f -> f.detail().contains("wrong")), "stale or foreign browser run events are ignored");
      }
    }
    System.out.println("ScopeInspectionRunnerTest: " + checks + " checks passed (production runner, isolated loopback HTTP)");
  }
}
