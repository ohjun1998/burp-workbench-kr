package kr.workbench;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Grounded task parsing and bounded/redacted data snapshots; no network or model required. */
public final class LocalLlmTasksTest extends WorkbenchTest {
  static LocalLlmTasks.Source source(String id) {
    return new LocalLlmTasks.Source(id, "GET", "https://app.test/orders/7", "A · 사용자 선언",
        "GET /orders/7 HTTP/1.1\r\nHost: app.test\r\n\r\n",
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ownerId\":7,\"orderId\":9}");
  }
  static Map<String, Object> row(String id, String title) {
    return Map.of("title", title, "category", "주문 조회", "source_ids", List.of(id),
        "observation", "주문 조회 응답 관측", "uncertainty", "공유 정책은 미확인", "next_check", "계정·소유 관계 확인");
  }
  static String result(List<?> rows, String report) {
    return MetadataExport.json(Map.of("rows", rows, "report", report));
  }
  static HttpRequestResponse packet(byte[] raw) {
    var request = request("https://app.test/data", "GET", true, List.of());
    HttpResponse response = proxy(HttpResponse.class, (p, m, a) -> m.getName().equals("toByteArray") ? bytes(raw) : def(m));
    return proxy(HttpRequestResponse.class, (p, m, a) -> switch (m.getName()) {
      case "request" -> request; case "response" -> response; default -> def(m);
    });
  }
  static byte[] raw(String headers, byte[] body) throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    out.write(("HTTP/1.1 200 OK\r\n" + headers + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    out.write(body); return out.toByteArray();
  }
  static byte[] gzip(String text) throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    try (var zip = new java.util.zip.GZIPOutputStream(out)) { zip.write(text.getBytes(StandardCharsets.UTF_8)); }
    return out.toByteArray();
  }
  public static void main(String[] args) throws Exception {
    var first = source("disk:1");
    var second = source("disk:2");
    var input = new LocalLlmTasks.Input(LocalLlmTasks.Task.FUNCTIONS, "주문 화면에서 수집", List.of(first, second));
    String encoded = LocalLlmTasks.user(input);
    check(encoded.contains("disk:1") && encoded.contains("disk:2") && encoded.contains("ownerId"),
        "immutable IDs and owner relation preserved in bounded task JSON");
    check(((Map<?, ?>) MinerJson.parse(encoded)).get("task").equals("FUNCTIONS"), "task request uses data-only JSON");
    var parsed = LocalLlmTasks.parse(result(List.of(row("disk:1", "주문 상세")), ""), input);
    check(parsed.rows().size() == 2 && parsed.rows().get(1).category().equals("미분류")
        && parsed.rows().get(1).sourceIds().equals(List.of("disk:2")), "omitted classification sources become explicit unknowns");
    var grouped = LocalLlmTasks.parse(result(List.of(row("disk:1", "주문"), row("disk:1", "공유 API"), row("disk:2", "조회")), ""), input);
    check(grouped.rows().size() == 3 && grouped.rows().get(1).sourceIds().equals(List.of("disk:1")),
        "shared API can link to several groups without merging or rewriting originals");
    var duplicated = new LinkedHashMap<>(row("disk:1", "주문")); duplicated.put("source_ids", List.of("disk:1", "disk:1"));
    check(LocalLlmTasks.parse(result(List.of(duplicated), ""), input).rows().get(0).sourceIds().size() == 1,
        "duplicate grounding links within one row deduplicated");
    rejects(() -> LocalLlmTasks.parse(result(List.of(row("invented:3", "거짓 원본")), ""), input), "unknown model-generated ID rejected as a whole");
    rejects(() -> LocalLlmTasks.parse(result(List.of(), ""), input), "empty model output not counted as a completed analysis");
    rejects(() -> LocalLlmTasks.parse("{\"rows\":[],\"rows\":[]}", input), "duplicate JSON output keys rejected");
    rejects(() -> LocalLlmTasks.parse("not JSON", input), "prose output rejected");
    var wrongType = new LinkedHashMap<>(row("disk:1", "주문")); wrongType.put("source_ids", "disk:1");
    rejects(() -> LocalLlmTasks.parse(result(List.of(wrongType), ""), input), "wrong source ID shape rejected");
    var noUncertainty = new LinkedHashMap<>(row("disk:1", "주문")); noUncertainty.remove("uncertainty");
    rejects(() -> LocalLlmTasks.parse(result(List.of(noUncertainty), ""), input), "required uncertainty missing stays incomplete");
    var reportInput = new LocalLlmTasks.Input(LocalLlmTasks.Task.REPORT, "검증 결과는 후보", List.of(first));
    rejects(() -> LocalLlmTasks.parse(result(List.of(row("disk:1", "보고서")), "영향 확정"), reportInput),
        "ungrounded free report rejected");
    check(LocalLlmTasks.parse(result(List.of(row("disk:1", "보고서")), "[disk:1] 실제 관측과 미확인 영향"), reportInput).report().contains("disk:1"),
        "report draft keeps actual evidence link");
    check(LocalLlmTasks.parse("```json\n" + result(List.of(row("disk:1", "주문")), "") + "\n```", input).rows().size() == 2,
        "one explicit JSON code fence is accepted without executing content");
    for (var task : LocalLlmTasks.Task.values()) {
      check(LocalLlmTasks.system(task).contains("신뢰할 수 없는") && LocalLlmTasks.system(task).contains("HTTP 전송"),
          "every task treats remote instructions as data: " + task);
    }
    check(LocalLlmTasks.preview(input).contains(encoded), "preview contains exact outbound task JSON");
    rejects(() -> new LocalLlmTasks.Input(LocalLlmTasks.Task.RESPONSE, "", List.of()), "empty input rejected before networking");
    rejects(() -> new LocalLlmTasks.Input(LocalLlmTasks.Task.RESPONSE, "", List.of(first, first)), "duplicate immutable original ID rejected");
    List<LocalLlmTasks.Source> many = new ArrayList<>();
    for (int i = 0; i < 25; i++) many.add(source("id:" + i));
    rejects(() -> new LocalLlmTasks.Input(LocalLlmTasks.Task.RESPONSE, "", many), "source-count ceiling enforced");
    rejects(() -> new LocalLlmTasks.Input(LocalLlmTasks.Task.RESPONSE, "x".repeat(32 * 1024 + 1), List.of(first)),
        "oversized context rejected rather than silently dropping policy");
    List<LocalLlmTasks.Source> large = new ArrayList<>();
    for (int i = 0; i < 10; i++) large.add(new LocalLlmTasks.Source("large:" + i, "GET", "https://app.test/", "A",
        "GET / HTTP/1.1\r\n\r\n" + "r".repeat(9000), "HTTP/1.1 200 OK\r\n\r\n" + "s".repeat(9000)));
    rejects(() -> new LocalLlmTasks.Input(LocalLlmTasks.Task.RESPONSE, "", large), "total JSON input ceiling includes every preview");

    String q = "POST /orders?token=QUERY_PRIVATE&ownerId=7 HTTP/1.1\r\nHost: app.test\r\nCookie: sid=COOKIE_PRIVATE; csrf=CSRF_PRIVATE\r\nAuthorization: Bearer AUTH_PRIVATE\r\nContent-Type: application/json\r\n\r\n"
        + "{\"password\":\"PASSWORD_PRIVATE\",\"ownerId\":7,\"userEmail\":\"alice@example.com\"}";
    String s = "HTTP/1.1 200 OK\r\nSet-Cookie: sid=ISSUED_PRIVATE; Path=/; Max-Age=3600\r\nContent-Type: application/json\r\n\r\n"
        + "{\"token\":\"TOKEN_PRIVATE\",\"echo\":\"PASSWORD_PRIVATE COOKIE_PRIVATE AUTH_PRIVATE QUERY_PRIVATE ISSUED_PRIVATE\",\"ownerId\":7,\"email\":\"alice@example.com\"}";
    var safe = new LocalLlmTasks.Source("sensitive:1", "POST", "https://app.test/orders?token=QUERY_PRIVATE&ownerId=7", "A", q, s);
    String safeText = LocalLlmTasks.user(new LocalLlmTasks.Input(LocalLlmTasks.Task.AUTH, "", List.of(safe)));
    for (String secret : List.of("QUERY_PRIVATE", "COOKIE_PRIVATE", "CSRF_PRIVATE", "AUTH_PRIVATE", "PASSWORD_PRIVATE", "ISSUED_PRIVATE", "TOKEN_PRIVATE", "alice@example.com"))
      check(!safeText.contains(secret), "credential/PII hidden before outbound preview: " + secret);
    check(safeText.contains("ownerId") && safeText.contains("7") && safeText.contains("email:"), "owner fields and stable email equality aliases preserved");
    check(q.contains("PASSWORD_PRIVATE") && s.contains("ISSUED_PRIVATE"), "source packet strings remain unchanged");
    String form = "POST / HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\npassword=FORM_PRIVATE&ownerId=7";
    check(!new LocalLlmTasks.Source("form:1", "POST", "https://app.test/", "A", form, "").request().contains("FORM_PRIVATE"), "form credential masked");
    String js = "HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\n\r\nconst apiToken='JS_PRIVATE'; fetch('/api/orders'); const ownerId=7;";
    String jsPreview = new LocalLlmTasks.Source("js:1", "GET", "https://app.test/main.js", "", "", js).response();
    check(jsPreview.contains("/api/orders") && !jsPreview.contains("JS_PRIVATE"), "JS API path retained while credential assignment hidden");
    String xml = "POST /soap HTTP/1.1\r\nContent-Type: text/xml\r\n\r\n<Body><AuthInfo><id>XML_PRIVATE</id></AuthInfo><orderId>7</orderId></Body>";
    String xmlPreview = new LocalLlmTasks.Source("xml:1", "POST", "https://app.test/soap", "", xml, "").request();
    check(!xmlPreview.contains("XML_PRIVATE") && xmlPreview.contains("orderId"), "SOAP authentication envelope hidden while operation fields retained");
    String context = "Authorization: Bearer CONTEXT_PRIVATE\npassword=BARE_PRIVATE\n{\"token\":\"JSON_PRIVATE\",\"ownerId\":7}";
    String contextSafe = new LocalLlmTasks.Input(LocalLlmTasks.Task.AUTH, context, List.of(first)).context();
    check(!contextSafe.contains("CONTEXT_PRIVATE") && !contextSafe.contains("BARE_PRIVATE") && !contextSafe.contains("JSON_PRIVATE")
        && contextSafe.contains("ownerId"), "manually edited context is redacted on request snapshot");
    String invalidJson = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"password\":12345,";
    check(!new LocalLlmTasks.Source("invalid:1", "GET", "https://app.test/", "", "", invalidJson).response().contains("12345"),
        "malformed structured body excluded instead of leaking unparsed credentials");
    AtomicInteger fullReads = new AtomicInteger(), sliceReads = new AtomicInteger();
    byte[] huge = ("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" + "x".repeat(100_000)).getBytes(StandardCharsets.UTF_8);
    ByteArray wire = proxy(ByteArray.class, (p, m, a) -> switch (m.getName()) {
      case "length" -> huge.length;
      case "getBytes" -> { fullReads.incrementAndGet(); throw new AssertionError("unbounded getBytes"); }
      case "subArray" -> { check((int) a[0] == 0 && (int) a[1] == LocalLlmTasks.INSPECT_BYTES, "host slice is bounded before copying"); sliceReads.incrementAndGet(); yield bytes(Arrays.copyOf(huge, (int) a[1])); }
      default -> def(m);
    });
    var request = request("https://app.test/huge", "GET", true, List.of());
    HttpResponse response = proxy(HttpResponse.class, (p, m, a) -> m.getName().equals("toByteArray") ? wire : def(m));
    HttpRequestResponse packet = proxy(HttpRequestResponse.class, (p, m, a) -> switch (m.getName()) {
      case "request" -> request; case "response" -> response; default -> def(m);
    });
    var captured = LocalLlmTasks.capture("huge:1", packet, "A");
    check(fullReads.get() == 0 && sliceReads.get() == 1 && captured.response().length() <= LocalLlmTasks.PREVIEW_CHARS,
        "large body never copied whole and outbound side bounded to8KiB");
    check(captured.response().contains("부분 입력"), "partial source clearly marked for model and preview");
    String gzipBody = "{\"ownerId\":7,\"label\":\"한국어 주문\",\"token\":\"COMPRESSED_PRIVATE\"}";
    String gzipPreview = LocalLlmTasks.capture("gzip:1", packet(raw("Content-Encoding: gzip\r\nContent-Type: application/json; charset=UTF-8", gzip(gzipBody))), "A").response();
    check(gzipPreview.contains("한국어 주문") && gzipPreview.contains("ownerId") && !gzipPreview.contains("COMPRESSED_PRIVATE"),
        "bounded gzip decoded before redaction with owner and Korean text retained");
    String bombPreview = LocalLlmTasks.capture("gzip:2", packet(raw("Content-Encoding: gzip\r\nContent-Type: text/plain", gzip("x".repeat(100_000)))), "A").response();
    check(bombPreview.contains("본문 미분석") && bombPreview.length() < 1024, "compressed expansion ceiling yields explicit omission");
    String charsetPreview = LocalLlmTasks.capture("charset:1", packet(raw("Content-Type: text/html; charset=windows-949", "<h1>한국어 주문 조회</h1>".getBytes(java.nio.charset.Charset.forName("MS949")))), "A").response();
    check(charsetPreview.contains("한국어 주문 조회"), "declared Korean charset decoded without editing original bytes");
    String binaryPreview = LocalLlmTasks.capture("binary:1", packet(raw("Content-Type: application/octet-stream", new byte[] {0, 1, 2, 3, 4})), "A").response();
    check(binaryPreview.contains("본문 미분석") && binaryPreview.contains("바이너리"), "binary response explicitly excluded instead of gibberish");
    String unknownEncoding = LocalLlmTasks.capture("encoding:1", packet(raw("Content-Encoding: zstd\r\nContent-Type: text/plain", new byte[] {1, 2, 3})), "A").response();
    check(unknownEncoding.contains("본문 미분석"), "unsupported content encoding yields clear incomplete input");
    System.out.println("LocalLlmTasksTest: " + checks + " checks passed");
  }
}
