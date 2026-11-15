package kr.workbench;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real isolated loopback transport, including cancellation and oversized/chunked responses. */
public final class LocalLlmClientTest {
  private static int checks;
  private static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    checks++;
  }
  @FunctionalInterface private interface Call { void run() throws Exception; }
  private static String rejected(Call action) throws Exception {
    try { action.run(); throw new AssertionError("expected explicit failure"); }
    catch (IOException | IllegalArgumentException expected) { checks++; return expected.getMessage(); }
  }
  public static void main(String[] args) throws Exception {
    config();
    transport();
    System.out.println("LocalLlmClientTest: " + checks + " checks passed");
  }
  private static void config() throws Exception {
    var defaults = LocalLlmConfig.defaults();
    check(defaults.baseUrl().equals("http://127.0.0.1:8731/v1") && defaults.maxTokens() == 8192,
        "default API and budget");
    for (String url : List.of("http://localhost:8731", "http://127.0.0.1:9/v1/",
        "http://10.0.0.1/v1", "https://172.31.0.1/v1", "http://192.168.1.8/v1",
        "http://[::1]:8731/v1", "http://[fd00::1]/v1"))
      check(new LocalLlmConfig(url, "fixture", "", 5, 256, "").baseUrl().endsWith("/v1"),
          "local endpoints normalized");
    for (String url : List.of("https://api.example.com/v1", "http://127.0.0.1.attacker.test/v1",
        "http://8.8.8.8/v1", "http://172.32.0.1/v1", "http://169.254.169.254/v1",
        "http://user:secret@localhost/v1", "http://127.0.0.1/v1?key=secret",
        "http://127.0.0.1/v1#fragment", "http://127.0.0.1/private/v1",
        "http://127.1/v1", "http://0177.0.0.1/v1", "http://[2001:4860::1]/v1"))
      rejected(() -> new LocalLlmConfig(url, "fixture", "", 5, 256, ""));
    rejected(() -> new LocalLlmConfig(defaults.baseUrl(), "model\nheader", "", 5, 256, ""));
    rejected(() -> new LocalLlmConfig(defaults.baseUrl(), "fixture", "key\r\nheader", 5, 256, ""));
    check(!new LocalLlmConfig(defaults.baseUrl(), "fixture", "session-key", 5, 256, "")
        .toString().contains("session-key"), "diagnostic string never exposes API key");
  }
  private static void transport() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newFixedThreadPool(4, action -> {
      Thread thread = new Thread(action, "llm-loopback-fixture"); thread.setDaemon(true); return thread;
    });
    AtomicReference<String> mode = new AtomicReference<>("ok");
    AtomicReference<Map<?, ?>> captured = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicInteger posts = new AtomicInteger(), redirected = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), bodyRelease = new CountDownLatch(1);
    server.setExecutor(executor);
    server.createContext("/", exchange -> {
      try {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/health")) {
          reply(exchange, 200, mode.get().equals("structured")
              ? "{\"version\":\"fixture\",\"structured_output\":true}"
              : "{\"version\":\"fixture\",\"structured_output\":false}"); return;
        }
        if (path.equals("/v1/models")) {
          reply(exchange, 200, "{\"data\":[{\"id\":\"fixture-model\"},{\"id\":\"\"},{\"id\":\"bad\\nmodel\"}]}"); return;
        }
        if (path.equals("/redirected")) { redirected.incrementAndGet(); reply(exchange, 200, "{}"); return; }
        posts.incrementAndGet();
        captured.set((Map<?, ?>) MinerJson.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        switch (mode.get()) {
          case "redirect" -> { exchange.getResponseHeaders().set("Location", "/redirected"); reply(exchange, 307, "{}"); }
          case "length" -> reply(exchange, 200, completion("partial-private-body", "length"));
          case "empty" -> reply(exchange, 200, completion("", "stop"));
          case "missing" -> reply(exchange, 200, "{\"choices\":[]}");
          case "malformed" -> reply(exchange, 200, "private-server-error-not-json");
          case "error" -> reply(exchange, 401, "private-server-error-session-key");
          case "large" -> reply(exchange, 200, "x".repeat(LocalLlmClient.RESPONSE_LIMIT + 1));
          case "body-timeout" -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
              output.write('{'); output.flush();
              try { bodyRelease.await(5, TimeUnit.SECONDS); }
              catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
              output.write('}');
            }
          }
          case "wait" -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            reply(exchange, 200, completion("late", "stop"));
          }
          default -> reply(exchange, 200, completion("{\"summary\":\"fixture\"}", "stop"));
        }
      } catch (IOException clientClosed) { exchange.close(); }
    });
    server.start();
    LocalLlmConfig config = new LocalLlmConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
        "fixture-model", "session-key", 5, 8192, "low");
    try (LocalLlmClient client = new LocalLlmClient()) {
      var result = client.complete(config, "system-instruction", "packet-data", false);
      check(result.text().contains("summary") && result.finishReason().equals("stop"), "valid completed output");
      check(result.promptTokens() == 42 && result.completionTokens() == 12 && result.elapsedMillis() >= 0,
          "usage and elapsed metrics");
      check(result.timings().contains("prompt_ms") && !result.timings().contains("private"), "numeric-only timings");
      check(captured.get().get("model").equals("fixture-model") && authorization.get().equals("Bearer session-key"),
          "configured model and in-memory key");
      check(((List<?>) captured.get().get("messages")).size() == 2
          && captured.get().get("reasoning_effort").equals("low") && captured.get().get("stream").equals(false),
          "data and system messages with configured reasoning");
      check(!captured.get().containsKey("tools") && !captured.get().containsKey("response_format"),
          "no executable tools or unsupported constrained output");
      check(client.health(config).contains("fixture") && client.models(config).equals(List.of("fixture-model")),
          "explicit health and bounded model discovery");
      int before = posts.get();
      rejected(() -> client.complete(config, "system", "data", true));
      check(posts.get() == before, "unsupported structured output is rejected before generation");
      mode.set("structured");
      client.complete(config, "system", "data", true);
      check(captured.get().get("response_format") instanceof Map<?, ?> map
          && map.get("type").equals("json_object"), "supported structured output is opt-in");
      for (String failure : List.of("redirect", "length", "empty", "missing", "malformed", "error", "large")) {
        mode.set(failure);
        String message = rejected(() -> client.complete(config, "system", "data", false));
        check(!message.contains("private") && !message.contains("session-key"), "errors do not echo response or credentials");
      }
      check(redirected.get() == 0, "redirect destination never receives a request");
      before = posts.get();
      rejected(() -> client.complete(config, "system", "x".repeat(LocalLlmClient.REQUEST_LIMIT), false));
      check(posts.get() == before, "oversized request is rejected before network I/O");
      mode.set("body-timeout");
      LocalLlmConfig impatient = new LocalLlmConfig(config.baseUrl(), config.model(), config.apiKey(), 1, 8192, "low");
      long timed = System.nanoTime();
      String timeout = rejected(() -> client.complete(impatient, "system", "data", false));
      check(timeout.contains("제한 시간") && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - timed) < 2500,
          "whole-body deadline also covers stalled chunked body after headers");
      bodyRelease.countDown();
      mode.set("wait");
      var worker = Executors.newSingleThreadExecutor();
      try {
        var pending = worker.submit(() -> {
          try { client.complete(config, "system", "data", false); return "success"; }
          catch (Exception failure) { return failure.getMessage(); }
        });
        check(entered.await(3, TimeUnit.SECONDS), "fixture receives cancellable request");
        long stopped = System.nanoTime();
        client.cancel();
        check(pending.get(2, TimeUnit.SECONDS).contains("중지"), "cancel returns explicit incomplete outcome");
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopped) < 2000, "cancel does not wait for prefill");
        release.countDown();
      } finally { worker.shutdownNow(); }
      mode.set("ok");
      check(client.complete(config, "system", "data", false).finishReason().equals("stop"),
          "new explicit request works after cancellation");
      client.close();
      rejected(() -> client.health(config));
    } finally {
      release.countDown();
      bodyRelease.countDown();
      server.stop(0);
      executor.shutdownNow();
    }
  }
  private static String completion(String content, String reason) {
    return MetadataExport.json(Map.of("choices", List.of(Map.of("message", Map.of("content", content),
        "finish_reason", reason)), "usage", Map.of("prompt_tokens", 42, "completion_tokens", 12),
        "timings", Map.of("prompt_ms", 12.5, "predicted_ms", 18, "private", "private-server-value")));
  }
  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, 0); // chunked, so the client must enforce the streaming limit
    try (var output = exchange.getResponseBody()) { output.write(bytes); }
  }
}
