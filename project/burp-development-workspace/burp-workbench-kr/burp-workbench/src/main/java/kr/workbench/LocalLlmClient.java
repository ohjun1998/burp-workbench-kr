package kr.workbench;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Explicit local API calls only. Never follows redirects, executes tools or retries a request. */
final class LocalLlmClient implements AutoCloseable {
  static final int RESPONSE_LIMIT = 512 * 1024;
  static final int REQUEST_LIMIT = 256 * 1024;
  record Reply(String text, String finishReason, int promptTokens, int completionTokens,
               long elapsedMillis, String timings) {}

  private final Object lock = new Object();
  private final HttpClient client = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NEVER)
      .proxy(new ProxySelector() {
        public List<java.net.Proxy> select(URI uri) { return List.of(java.net.Proxy.NO_PROXY); }
        public void connectFailed(URI uri, java.net.SocketAddress address, IOException error) {}
      }).connectTimeout(Duration.ofSeconds(10)).build();
  private CompletableFuture<?> active;
  private long generation;
  private boolean closed;

  Reply complete(LocalLlmConfig config, String system, String user, boolean structured) throws Exception {
    long started = System.nanoTime();
    long operationGeneration = currentGeneration();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", config.model());
    body.put("messages", List.of(Map.of("role", "system", "content", system),
        Map.of("role", "user", "content", user)));
    body.put("stream", false);
    body.put("temperature", 0);
    body.put("max_tokens", config.maxTokens());
    if (!config.reasoningEffort().isEmpty()) body.put("reasoning_effort", config.reasoningEffort());
    if (structured) {
      Object state = json(health(config, operationGeneration));
      if (!(state instanceof Map<?, ?> map) || !structuredSupported(map.get("structured_output")))
        throw new IOException("현재 /health에서 JSON 출력 지원을 확인하지 못했습니다. 일반 JSON 지시 모드를 사용하세요.");
      body.put("response_format", Map.of("type", "json_object"));
    }
    Object decoded = json(exchange(config, config.baseUrl() + "/chat/completions",
        MetadataExport.json(body), operationGeneration));
    if (!(decoded instanceof Map<?, ?> root) || !(root.get("choices") instanceof List<?> choices)
        || choices.isEmpty() || !(choices.get(0) instanceof Map<?, ?> choice)
        || !(choice.get("message") instanceof Map<?, ?> message))
      throw new IOException("LLM 응답에 choices/message가 없습니다. 분석 미완료입니다.");
    String finish = string(choice.get("finish_reason"));
    if (finish.equals("length"))
      throw new IOException("출력·추론 예산이 소진됐습니다(finish_reason=length). 분석 미완료 · 예산 또는 추론 수준을 조정하세요.");
    if (!finish.equals("stop"))
      throw new IOException("LLM 응답이 정상 종료되지 않았습니다. 분석 미완료입니다.");
    String content = string(message.get("content")).strip();
    if (content.isEmpty()) throw new IOException("LLM의 최종 답변이 비어 있습니다. 분석 미완료입니다.");
    Map<?, ?> usage = root.get("usage") instanceof Map<?, ?> map ? map : Map.of();
    Map<?, ?> timings = root.get("timings") instanceof Map<?, ?> map ? map : Map.of();
    // Keep only numeric engine timing values; arbitrary server strings never enter diagnostics.
    Map<String, Object> safeTimings = new LinkedHashMap<>();
    for (String field : List.of("prompt_n", "predicted_n", "prompt_ms", "predicted_ms",
        "cache_n", "prompt_per_second", "predicted_per_second"))
      if (timings.get(field) instanceof Number value) safeTimings.put(field, value);
    return new Reply(content, finish, count(usage.get("prompt_tokens")),
        count(usage.get("completion_tokens")), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        MetadataExport.json(safeTimings));
  }

  String health(LocalLlmConfig config) throws Exception {
    return health(config, currentGeneration());
  }

  private String health(LocalLlmConfig config, long operationGeneration) throws Exception {
    URI base = URI.create(config.baseUrl());
    String response = exchange(config, base.getScheme() + "://" + base.getRawAuthority() + "/health",
        null, operationGeneration);
    json(response);
    return response;
  }

  List<String> models(LocalLlmConfig config) throws Exception {
    Object value = json(exchange(config, config.baseUrl() + "/models", null, currentGeneration()));
    if (!(value instanceof Map<?, ?> root) || !(root.get("data") instanceof List<?> data))
      throw new IOException("모델 목록 응답 형식을 확인하세요.");
    List<String> models = new ArrayList<>();
    for (Object item : data) if (item instanceof Map<?, ?> model && model.get("id") instanceof String id
        && !id.isBlank() && id.length() <= 200 && id.chars().noneMatch(c -> c < 32 || c == 127)) {
      if (!models.contains(id)) models.add(id);
      if (models.size() == 32) break;
    }
    return List.copyOf(models);
  }

  private static boolean structuredSupported(Object field) {
    if (Boolean.TRUE.equals(field)) return true;
    if (!(field instanceof Map<?, ?> map)) return false;
    return Boolean.TRUE.equals(map.get("json_object"))
        || map.get("supported") instanceof List<?> list && list.contains("json_object");
  }

  private long currentGeneration() throws IOException {
    synchronized (lock) {
      if (closed) throw new IOException("LLM 연결이 종료되었습니다.");
      return generation;
    }
  }

  private String exchange(LocalLlmConfig config, String endpoint, String body, long expectedGeneration) throws Exception {
    if (body != null && body.getBytes(StandardCharsets.UTF_8).length > REQUEST_LIMIT)
      throw new IOException("LLM 요청의 256 KiB 한도를 초과했습니다. 원본을 나누어 분석하세요.");
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
        .timeout(Duration.ofSeconds(config.timeoutSeconds())).header("Accept", "application/json");
    if (!config.apiKey().isEmpty()) builder.header("Authorization", "Bearer " + config.apiKey());
    if (body == null) builder.GET();
    else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    CompletableFuture<HttpResponse<byte[]>> future;
    long requestGeneration;
    synchronized (lock) {
      if (closed) throw new IOException("LLM 연결이 종료되었습니다.");
      if (generation != expectedGeneration) throw new IOException("LLM 분석을 중지했습니다.");
      if (active != null) throw new IOException("이전 LLM 요청이 처리 중입니다.");
      requestGeneration = generation;
      future = client.sendAsync(builder.build(), ignored -> new BoundedBody());
      active = future;
    }
    try {
      HttpResponse<byte[]> response = future.get(config.timeoutSeconds(), TimeUnit.SECONDS);
      synchronized (lock) {
        if (generation != requestGeneration) throw new IOException("LLM 분석을 중지했습니다.");
      }
      if (response.statusCode() != 200)
        throw new IOException("LLM 서버 HTTP " + response.statusCode() + " · 주소·모델·인증과 지원 옵션을 확인하세요.");
      return new String(response.body(), StandardCharsets.UTF_8);
    } catch (TimeoutException error) {
      future.cancel(true);
      throw new IOException("LLM 응답 제한 시간을 초과했습니다. 분석 미완료입니다.");
    } catch (InterruptedException error) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new IOException("LLM 분석을 중지했습니다.");
    } catch (java.util.concurrent.CancellationException error) {
      throw new IOException("LLM 분석을 중지했습니다.");
    } catch (java.util.concurrent.ExecutionException error) {
      synchronized (lock) {
        if (generation != requestGeneration) throw new IOException("LLM 분석을 중지했습니다.");
      }
      throw new IOException("로컬 LLM 연결·응답 수신 실패 · 서버 상태·응답 크기(512 KiB 한도)를 확인하세요.");
    } finally {
      synchronized (lock) { if (active == future) active = null; }
    }
  }

  private static Object json(String text) throws IOException {
    try { return MinerJson.parse(text, RESPONSE_LIMIT, 20000); }
    catch (RuntimeException error) { throw new IOException("LLM 서버의 JSON 응답 형식을 확인하세요."); }
  }
  private static int count(Object value) {
    if (!(value instanceof Number number)) return 0;
    long count = number.longValue();
    return count >= 0 && count <= Integer.MAX_VALUE ? (int) count : 0;
  }
  private static String string(Object value) { return value instanceof String text ? text : ""; }

  void cancel() {
    synchronized (lock) {
      generation++;
      if (active != null) active.cancel(true);
    }
  }
  @Override public void close() {
    synchronized (lock) { closed = true; cancel(); }
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    final CompletableFuture<byte[]> body = new CompletableFuture<>();
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    Flow.Subscription subscription;
    public CompletionStage<byte[]> getBody() { return body; }
    public void onSubscribe(Flow.Subscription value) {
      if (subscription != null) { value.cancel(); return; }
      subscription = value;
      body.whenComplete((ignored, error) -> { if (body.isCancelled() && subscription != null) subscription.cancel(); });
      value.request(1);
    }
    public void onNext(List<ByteBuffer> buffers) {
      if (body.isDone()) return;
      for (ByteBuffer buffer : buffers) {
        int remaining = buffer.remaining();
        if (remaining > RESPONSE_LIMIT - output.size()) {
          subscription.cancel();
          body.completeExceptionally(new IOException("LLM response size limit"));
          return;
        }
        byte[] bytes = new byte[remaining];
        buffer.get(bytes);
        output.writeBytes(bytes);
      }
      subscription.request(1);
    }
    public void onError(Throwable error) { body.completeExceptionally(new IOException("LLM response failed")); }
    public void onComplete() { body.complete(output.toByteArray()); }
  }
}
