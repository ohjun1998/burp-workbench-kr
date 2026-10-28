package kr.workbench;

import java.util.*;

/** Real static analyzers and redacted inventory metadata; no target or key-validation network. */
public final class ScopeInspectionAnalysisTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    String secret = "ghp_" + "aB3dE6gH9jK2mN5pQ8sT1vW4yZ7cF0iL3oR6";
    String generic = "a7M2c9P4v1H8x5T0n3D6q9S2f7J4k1L8";
    String html = "<html><head><title>검사 예제</title></head><body>Apache Tomcat\n"
        + "<a href='/app/reports'>보고서</a><a href='#/settings?token=ROUTE_PRIVATE_TOKEN'>설정</a>\n"
        + "<script>fetch('/api/me'); const access_token='" + secret + "'; const api_key='" + generic + "';</script>\n"
        + "org.postgresql.util.PSQLException jdbc:postgresql://DB_USER:DB_PASSWORD@db.internal/app\n"
        + "internal=10.1.2.3 path=/var/app/private/config.json contact=user@example.test\n</body></html>";
    var result = ScopeInspectionAnalysis.analyze("https://inspect.test/app?token=URL_PRIVATE_TOKEN", 200,
        "Server: nginx/1.24.0\r\nContent-Type: text/html\r\nSet-Cookie: SID=COOKIE_PRIVATE_VALUE\r\nCF-Ray: example\r\n", html, "Alice", "packet-1");
    check(result.findings().stream().anyMatch(f -> f.category().equals("ENVIRONMENT") && f.title().contains("nginx")), "header stack signal is retained");
    check(result.findings().stream().anyMatch(f -> f.title().equals("nginx 버전 표식 1.24.0") && f.detail().contains("Server")), "explicit numeric product version is linked to its observed header");
    check(result.findings().stream().anyMatch(f -> f.category().equals("ENVIRONMENT") && f.title().contains("Tomcat")), "WAS body signal is retained separately from front server");
    check(result.findings().stream().anyMatch(f -> f.category().equals("ENVIRONMENT") && f.title().contains("Cloudflare")), "proxy/CDN evidence has separate classification");
    check(result.findings().stream().filter(f -> Set.of("ENVIRONMENT", "DATABASE").contains(f.category())).allMatch(f -> !f.confidence().equals("확정")), "HTTP stack evidence never claims actual deployed products are proved");
    check(result.findings().stream().anyMatch(f -> f.category().equals("DATABASE") && f.title().equals("PostgreSQL") && f.confidence().equals("추정")), "database exception produces an explained product hypothesis");
    check(result.candidates().stream().anyMatch(c -> c.source().equals("JS API") && c.url().contains("/api/me")), "real JS analyzer discovers an API candidate");
    var methodEvidence = ScopeInspectionAnalysis.analyze("https://inspect.test/app.js", 200, "Content-Type: application/javascript",
        "const endpoint='/api/method-unknown'; axios.post('/api/write-only', {});", "anonymous", "method-evidence");
    check(methodEvidence.candidates().stream().noneMatch(c -> c.executable() && c.method().equals("GET")
        && (c.url().endsWith("/api/method-unknown") || c.url().endsWith("/api/write-only"))), "generic quoted URL discovery cannot invent GET for unknown or explicit POST APIs");
    check(result.candidates().stream().anyMatch(c -> c.source().equals("메뉴 라우트") && !c.executable()), "hash menu route requires browser traversal instead of an invented GET");
    check(result.findings().stream().anyMatch(f -> f.category().equals("SECRET") && f.originalId().equals("packet-1") && f.offset() >= 0), "secret findings retain original packet ID and exact source offset");
    check(result.findings().stream().anyMatch(f -> f.category().equals("INTERNAL")), "internal address/path observations are classified");
    String output = result.findings().toString() + result.candidates().stream().map(c -> c.label() + c.source()).toList() + result.note();
    for (String privateValue : List.of(secret, generic, "URL_PRIVATE_TOKEN", "COOKIE_PRIVATE_VALUE", "DB_PASSWORD", "ROUTE_PRIVATE_TOKEN"))
      check(!output.contains(privateValue), "summary never discloses private value: " + privateValue.substring(0, Math.min(6, privateValue.length())));
    check(result.findings().stream().noneMatch(f -> f.state().contains("유효함")), "key format findings never claim provider validity");
    check(result.findings().stream().filter(f -> f.category().equals("SECRET")).allMatch(f -> Set.of("높음", "중간", "낮음").contains(f.severity())), "secret severity is independent of format confidence");

    String doc = "{\"openapi\":\"3.0.3\",\"paths\":{"
        + "\"/api/public\":{\"get\":{}},"
        + "\"/api/required\":{\"get\":{\"parameters\":[{\"name\":\"account\",\"in\":\"query\",\"required\":true}]}},"
        + "\"/api/optional\":{\"get\":{\"parameters\":[{\"name\":\"q\",\"in\":\"query\"}]}},"
        + "\"/api/items/{id}\":{\"get\":{}},"
        + "\"/api/write\":{\"post\":{}},"
        + "\"/api/header\":{\"get\":{\"parameters\":[{\"$ref\":\"#/components/parameters/Identity\"}]}}},"
        + "\"components\":{\"parameters\":{\"Identity\":{\"name\":\"X-Identity\",\"in\":\"header\",\"required\":true}}}}";
    var api = ScopeInspectionAnalysis.analyze("https://inspect.test/openapi.json", 200, "Content-Type: application/json", doc, "anonymous", "doc-1");
    check(api.candidates().stream().anyMatch(c -> c.source().equals("API 문서") && c.url().endsWith("/api/public") && c.executable()), "resolved documented GET can be queued");
    check(api.candidates().stream().anyMatch(c -> c.source().equals("API 문서") && c.url().endsWith("/api/optional") && c.executable()), "optional query fields do not invent required values");
    for (String path : List.of("/api/required", "/api/header", "/api/items/{id}", "/api/write"))
      check(api.candidates().stream().anyMatch(c -> c.source().equals("API 문서") && c.url().endsWith(path) && !c.executable()), "unresolved or changing operation is left for review: " + path);
    check(api.candidates().stream().noneMatch(c -> c.executable() && c.method().equals("GET") && c.url().endsWith("/api/required")), "generic document discovery cannot override required-input execution gate");
    String incompleteDoc = "{\"openapi\":\"3.0.3\",\"paths\":{\"/api/no-method\":{\"summary\":\"unknown\"},\"/api/body-ref\":{\"get\":{\"requestBody\":{\"$ref\":\"missing.json\"}}}}}";
    var incompleteApi = ScopeInspectionAnalysis.analyze("https://inspect.test/openapi.json", 200, "Content-Type: application/json", incompleteDoc, "anonymous", "doc-partial");
    check(incompleteApi.candidates().stream().noneMatch(c -> c.source().equals("API 문서") && c.url().endsWith("/api/no-method")), "missing document method does not invent a GET API");
    check(incompleteApi.candidates().stream().anyMatch(c -> c.source().equals("API 문서") && c.url().endsWith("/api/body-ref") && !c.executable()), "unresolved request-body requirement cannot be queued as a complete GET");
    check(api.findings().stream().anyMatch(f -> f.url().contains("{id}")), "templated API remains visible without becoming a request");
    var pages = List.of(new ScopeInspection.Page("https://inspect.test/api/public", "GET", "anonymous", 200, "JSON", true, "", "digest", null),
        new ScopeInspection.Page("https://inspect.test/api/shadow", "GET", "anonymous", 200, "JSON", true, "", "digest", null),
        new ScopeInspection.Page("https://inspect.test/api/items/42", "GET", "anonymous", 403, "DENIED", true, "", "digest", null));
    var compared = ScopeInspectionAnalysis.compareInventory(api.findings(), api.candidates(), pages);
    check(compared.stream().anyMatch(f -> f.title().contains("대조되지 않은") && f.url().endsWith("/api/shadow")), "observed undocumented API is a review candidate");
    check(compared.stream().noneMatch(f -> f.title().contains("대조되지 않은") && f.url().endsWith("/api/items/42")), "documented template matches the observed concrete path");
    check(compared.stream().anyMatch(f -> f.title().contains("실행 미관측") && f.url().endsWith("/api/required")), "declared-only operations remain distinct from actual observations");
    check(ScopeInspectionAnalysis.compareInventory(List.of(), List.of(), pages).stream().anyMatch(f -> f.title().equals("API 문서 미확인")), "no document is not mislabeled a proven undocumented API");

    var examples = ScopeInspectionAnalysis.analyze("https://inspect.test/config.json", 200, "Content-Type: application/json",
        "{\"public_key\":\"pk_live_PUBLIC_IDENTIFIER_ABC123\",\"test_api_key\":\"EXAMPLE_DEMO_KEY_VALUE\",\"stripe\":\"sk_test_AbCdEfGh12345678XyZ9\"}", "anonymous", "example-1");
    check(examples.findings().stream().anyMatch(f -> f.state().contains("공개 식별자") && f.severity().equals("낮음")), "public identifiers receive low-priority review");
    check(examples.findings().stream().anyMatch(f -> f.state().contains("예제값") && f.severity().equals("낮음")), "test/example context is distinct from a live secret hypothesis");
    var conflict = ScopeInspectionAnalysis.analyze("https://inspect.test/error", 500, "Content-Type: text/plain", "ORA-00942 org.postgresql.util.PSQLException", "anonymous", "error-1");
    check(conflict.findings().stream().anyMatch(f -> f.title().equals("DB 단서 복수 관측") && f.confidence().equals("미확인")), "conflicting database products do not become a false definite stack");
    var oversized = ScopeInspectionAnalysis.analyze("https://inspect.test/large", 200, "Content-Type: text/plain", "x".repeat(ScopeInspectionAnalysis.BODY_LIMIT + 10), "anonymous", "large-1");
    check(!oversized.complete() && oversized.findings().stream().anyMatch(f -> f.category().equals("COVERAGE")), "source/time limits remain explicitly incomplete");
    var cors = ScopeInspectionAnalysis.analyze("https://inspect.test/", 200, "Content-Type: text/plain\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Credentials: true", "ok", "anonymous", "cors-1");
    check(cors.findings().stream().anyMatch(f -> f.title().equals("CORS 응답 정책 관측") && !f.state().contains("취약")), "CORS header observation is not a browser exploit verdict");
    String escaped = "<a href='/ok'>" + generic + "</a><script>const api_key='" + generic + "';</script>";
    var label = ScopeInspectionAnalysis.analyze("https://inspect.test/", 200, "Content-Type: text/html", escaped, "anonymous", "label-1");
    check(label.candidates().stream().noneMatch(c -> c.label().contains(generic) || c.source().contains(generic)), "candidate display labels redact detected secret values too");
    String encodedSecret = java.net.URLEncoder.encode(generic, java.nio.charset.StandardCharsets.UTF_8).replace("a", "%61");
    var secretPath = ScopeInspectionAnalysis.analyze("https://inspect.test/", 200, "Content-Type: text/html",
        "<a href='/verify/" + encodedSecret + "'>확인</a><script>const api_key='" + generic + "';</script>", "anonymous", "secret-path");
    check(secretPath.candidates().stream().anyMatch(c -> c.source().equals("관측 링크") && !c.executable() && c.url().contains("REDACTED")), "known secret URL inputs are masked and never replayed, including percent-encoding");
    check(secretPath.findings().stream().noneMatch(f -> f.url().contains(generic) || f.url().contains(encodedSecret)), "known encoded secret in menu URL cannot enter the persisted finding inventory");
    Set<String> privateValues = ScopeInspectionAnalysis.redactionValues("Authorization: Bearer " + secret + "\r\nSet-Cookie: SID=COOKIE_EVIDENCE_PRIVATE; Path=/", "const password='aBcD_eF12!'; const api_key='" + generic + "'; jdbc:postgresql://user:pass@db/app");
    check(privateValues.contains(secret) && privateValues.contains(generic) && privateValues.contains("aBcD_eF12!"), "explicit evidence handoff extracts provider and named secret values transiently");
    check(privateValues.contains("COOKIE_EVIDENCE_PRIVATE"), "evidence handoff includes actual session-cookie values");
    check(!privateValues.toString().contains(secret) && !privateValues.toString().contains(generic), "redaction-set diagnostics hide raw values");
    String pem = "-----BEGIN PRIVATE KEY-----\nABCD1234\n-----END PRIVATE KEY-----";
    check(ScopeInspectionAnalysis.redactionValues("", pem).contains(pem), "complete private-key span is available for redacted evidence handoff");
    check(!ScopeInspectionAnalysis.redactionValues("", "{\"password\":{\"type\":\"string\"},\"token\":true}").contains("{"), "credential field schema objects do not redact every JSON delimiter");
    rejects(() -> ScopeInspectionAnalysis.redactionValues("", "x".repeat(ScopeInspectionAnalysis.BODY_LIMIT + 1)), "oversized redaction input is not silently exported unreviewed");
    StringBuilder many = new StringBuilder(); for (int i = 0; i < 257; i++) many.append("api_key_").append(i).append("='AbCdEfGh").append(i).append("';");
    rejects(() -> ScopeInspectionAnalysis.redactionValues("", many.toString()), "redaction-value capacity fails explicitly instead of dropping values");
    System.out.println("ScopeInspectionAnalysisTest: " + checks + " checks passed (local analyzers; no network)");
  }
}
