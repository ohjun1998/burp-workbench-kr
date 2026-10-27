package kr.workbench;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Export-time secret discovery and provenance controls; no network requests. */
public final class EvidenceHardeningTest {
  private static int checks;
  private static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    checks++;
  }
  private static String response(String body) {
    return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" + body;
  }
  private static String entry(ZipFile zip, String name) throws Exception {
    return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
  }
  private static List<BountyEvidence.Item> items(String body) {
    return List.of(new BountyEvidence.Item("source", "GET", "https://lab.test/api", "GET /api HTTP/1.1\r\n\r\n", response(body), "manual"));
  }
  private static Map<?, ?> body(String packet) {
    return (Map<?, ?>) MinerJson.parse(packet.substring(packet.indexOf("\n\n") + 2));
  }

  public static void main(String[] args) throws Exception {
    var extra = BountyEvidence.extraNames(" CustomerCode, otp, X-Custom-Auth , customERC ");
    check(extra.size() == 4 && extra.contains("customercode"), "custom names normalized without values");
    for (String invalid : List.of("name\nvalue", "a".repeat(2049), String.join(",", java.util.stream.IntStream.range(0, 33).mapToObj(i -> "name" + i).toList()))) {
      try { BountyEvidence.extraNames(invalid); throw new AssertionError("invalid extra names accepted"); }
      catch (IllegalArgumentException expected) { check(true, "extra names limits reject invalid input"); }
    }
    String json = "{\"nested\":[{\"streamKey\":\"STREAM_PRIVATE_2026\",\"private_key\":\"PRIVATE_KEY_2026\",\"signing-key\":\"SIGNING_KEY_2026\",\"accessKey\":\"ACCESS_KEY_2026\",\"customerCode\":\"CUSTOMER_PRIVATE_2026\",\"otp\":\"698435\",\"videoId\":\"video-public\",\"clientId\":\"client-public\",\"publicKey\":\"public-key-value\",\"shipping\":\"shipping-public\"}]}";
    String raw = response(json);
    String packet = BountyEvidence.packet(raw, extra, List.of(), true);
    for (String value : List.of("STREAM_PRIVATE_2026", "PRIVATE_KEY_2026", "SIGNING_KEY_2026", "ACCESS_KEY_2026", "CUSTOMER_PRIVATE_2026", "698435"))
      check(!packet.contains(value), "nested service/custom credential hidden: " + value);
    check(packet.contains("video-public") && packet.contains("client-public") && packet.contains("public-key-value") && packet.contains("shipping-public"), "public identifiers and unrelated words remain useful evidence");
    String folded = "HTTP/1.1 200 OK\r\nAuthorization: Bearer\r\n FOLDED_PRIVATE_2026\r\nX-Custom-Auth: custom\r\n\tCUSTOM_FOLDED_2026\r\nX-Public: public\r\n continuation\r\n\r\n";
    packet = BountyEvidence.packet(folded, extra, List.of(), true);
    check(!packet.contains("FOLDED_PRIVATE_2026") && !packet.contains("CUSTOM_FOLDED_2026"), "folded authentication header continuation hidden");
    check(packet.contains("continuation"), "nonsecret continuation preserved");
    String form = "POST /submit HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\nCUSTOMERCODE=CUSTOMER_FORM_2026&stream%4bey=STREAM_FORM_2026&name=normal";
    packet = BountyEvidence.packet(form, extra, List.of(), true);
    check(!packet.contains("CUSTOMER_FORM_2026") && !packet.contains("STREAM_FORM_2026") && packet.contains("normal"), "form names decoded before case-insensitive redaction");

    String issued = "HTTP/1.1 200 OK\r\nSet-Cookie: NEW=ISSUED_COOKIE_2026; Path=/\r\nContent-Type: application/json\r\n\r\n" + json;
    String reflected = response("{\"echo\":\"ISSUED_COOKIE_2026 / STREAM_PRIVATE_2026 / CUSTOM_QUERY_2026\",\"same\":\"CUSTOMER_PRIVATE_2026\",\"safe\":\"retained public evidence\"}");
    String request = "GET /api?token=CUSTOM_QUERY_2026 HTTP/1.1\r\nHost: lab.test\r\n\r\n";
    var evidence = List.of(
        new BountyEvidence.Item("original", "GET", "https://lab.test/api?token=CUSTOM_QUERY_2026", request, issued, "STREAM_PRIVATE_2026 reflected in note"),
        new BountyEvidence.Item("second", "GET", "https://lab.test/read", "GET /read HTTP/1.1\r\n\r\n", reflected, "ISSUED_COOKIE_2026"));
    String preview = BountyEvidence.preview("title", "STREAM_PRIVATE_2026", "ISSUED_COOKIE_2026", evidence, extra, List.of(), true, false);
    for (String value : List.of("ISSUED_COOKIE_2026", "STREAM_PRIVATE_2026", "CUSTOM_QUERY_2026", "CUSTOMER_PRIVATE_2026"))
      check(!preview.contains(value), "newly discovered secret hidden across preview metadata and other packets: " + value);
    check(preview.contains("retained public evidence"), "nonsecret reflected response preserved");
    String escapedSecret = "quote\"slash\\credential";
    String escapedBody = MetadataExport.json(Map.of("streamKey", escapedSecret, "message", "prefix " + escapedSecret + " suffix"));
    String escapedPreview = BountyEvidence.preview("title", "", "", items(escapedBody), Set.of(), List.of(), true, false);
    check(!escapedPreview.contains("credential") && escapedPreview.contains("prefix "), "escaped reflected values are redacted before JSON serialization");
    String keySecret = "quote\"slash\\key-value";
    var keyed = body(BountyEvidence.packet(response(MetadataExport.json(Map.of(keySecret, "first", "REFLECTED_VALUE_2026", "second"))),
        Set.of(), List.of(keySecret, "REFLECTED_VALUE_2026"), true));
    check(keyed.size() == 2 && !keyed.containsKey(keySecret) && !keyed.containsKey("REFLECTED_VALUE_2026")
        && keyed.containsValue("first") && keyed.containsValue("second"), "reflected JSON keys are redacted before encoding without losing colliding entries");
    String keyedForm = "POST / HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\n"
        + java.net.URLEncoder.encode(keySecret, StandardCharsets.UTF_8) + "=public";
    check(BountyEvidence.packet(keyedForm, Set.of(), List.of(keySecret), true).endsWith("%5BREDACTED%5D=public"),
        "reflected form keys are redacted before percent encoding");
    Path zip = Files.createTempFile("evidence-hardening-", ".zip");
    try {
      String publicEscaped = "public\"quote\\path";
      String coverage = MetadataExport.json(Map.of("note", "before " + escapedSecret + " after", "public", publicEscaped,
          "nested", List.of(Map.of("customerCode", "coverage-private", "safe", 47))));
      var event = Map.<String, Object>of("method", "GET", "url", "https://lab.test/api", "note", escapedSecret,
          "nested", Map.of("customerCode", "event-private", "public", publicEscaped));
      BountyEvidence.save(zip, "title", "", "", items(escapedBody), extra, List.of(), true, coverage, null, List.of(event));
      try (ZipFile saved = new ZipFile(zip.toFile())) {
        var savedCoverage = (Map<?, ?>) MinerJson.parse(entry(saved, "coverage.json"));
        var savedEvent = (Map<?, ?>) ((List<?>) MinerJson.parse(entry(saved, "oast.json"))).get(0);
        check(savedCoverage.get("note").equals("before [REDACTED] after"), "coverage reflected credential with quotes and slashes is scrubbed before JSON encoding");
        check(savedEvent.get("note").equals("[REDACTED]"), "OAST reflected escaped credential is scrubbed before JSON encoding");
        check(savedCoverage.get("public").equals(publicEscaped)
            && ((Map<?, ?>) savedEvent.get("nested")).get("public").equals(publicEscaped), "unrelated escaped strings remain exact valid JSON values");
        check(((Map<?, ?>) ((List<?>) savedCoverage.get("nested")).get(0)).get("customerCode").equals("[REDACTED]")
            && ((Map<?, ?>) savedEvent.get("nested")).get("customerCode").equals("[REDACTED]"), "custom sensitive field names also apply to coverage and OAST structures");
        check(savedEvent.get("evidenceItems").toString().equals("[1]") && ((List<?>) savedEvent.get("packetIds")).size() == 1,
            "OAST evidence associations survive structural redaction");
      }
      String numericBody = "{\"pin\":4729,\"recordCount\":4729,\"publicCount\":4728,\"enabled\":true,\"missing\":null,\"huge\":1e999999}";
      BountyEvidence.save(zip, "title", "", "", items(numericBody), Set.of(), List.of("1"), true,
          "{\"count\":4729,\"publicCount\":4728,\"enabled\":true}", null,
          List.of(Map.of("method", "GET", "url", "https://lab.test/api", "count", 4729, "publicCount", 4728)));
      try (ZipFile saved = new ZipFile(zip.toFile())) {
        var packetBody = body(entry(saved, "responses/1.http"));
        var savedCoverage = (Map<?, ?>) MinerJson.parse(entry(saved, "coverage.json"));
        var savedEvent = (Map<?, ?>) ((List<?>) MinerJson.parse(entry(saved, "oast.json"))).get(0);
        check(packetBody.get("pin").equals("[REDACTED]") && packetBody.get("recordCount").equals("[REDACTED]"), "sensitive and reflected JSON numbers become valid quoted placeholders");
        check(savedCoverage.get("count").equals("[REDACTED]") && savedEvent.get("count").equals("[REDACTED]"), "numeric credential reflections are safely redacted in coverage and OAST");
        check(packetBody.get("publicCount") instanceof Number && packetBody.get("publicCount").toString().equals("4728")
            && savedCoverage.get("publicCount").toString().equals("4728") && savedEvent.get("publicCount").toString().equals("4728"), "unrelated JSON numbers retain their numeric type and value");
        check(Boolean.TRUE.equals(packetBody.get("enabled")) && packetBody.containsKey("missing") && packetBody.get("missing") == null,
            "boolean and null JSON values remain valid after numeric redaction");
        check(packetBody.get("huge").toString().equals("1E+999999"), "large numeric exponent is not expanded during secret matching");
        check(savedEvent.get("evidenceItems").toString().equals("[1]"), "generated evidence reference is preserved even when a short secret equals its index");
      }
      String cookiesRequest = "GET /api HTTP/1.1\r\nHost: lab.test\r\nCookie: FIRST=REQUEST_COOKIE_A; SECOND=REQUEST_COOKIE_B\r\n\r\n";
      String cookiesResponse = "HTTP/1.1 200 OK\r\nSet-Cookie: sid=RESPONSE_COOKIE; Max-Age=3600; Domain=lab.test; Path=/api\r\nContent-Type: application/json\r\n\r\n"
          + "{\"echo\":\"REQUEST_COOKIE_A REQUEST_COOKIE_B RESPONSE_COOKIE\",\"context\":\"lab.test public endpoint\",\"path\":\"/api\",\"ttl\":3600}";
      BountyEvidence.save(zip, "title", "", "", List.of(new BountyEvidence.Item("cookies", "GET", "https://lab.test/api", cookiesRequest, cookiesResponse, "")), Set.of(), List.of(), true, "{}", null);
      try (ZipFile saved = new ZipFile(zip.toFile())) {
        var packetBody = body(entry(saved, "responses/1.http"));
        check(packetBody.get("echo").equals("[REDACTED] [REDACTED] [REDACTED]"), "each request cookie and newly issued response cookie is hidden in reflected values");
        check(packetBody.get("context").equals("lab.test public endpoint") && packetBody.get("path").equals("/api")
            && packetBody.get("ttl") instanceof Number && ((Number) packetBody.get("ttl")).intValue() == 3600,
            "public Set-Cookie attributes do not become secret values");
        check(entry(saved, "requests/1.http").contains("Host: lab.test") && entry(saved, "report.md").contains("https://lab.test/api"),
            "cookie attributes do not redact public request conditions or report URLs");
      }
      BountyEvidence.save(zip, "title", "STREAM_PRIVATE_2026", "ISSUED_COOKIE_2026", evidence, extra, List.of(), true, "{}", null);
      try (ZipFile saved = new ZipFile(zip.toFile())) {
        StringBuilder all = new StringBuilder();
        for (var e : Collections.list(saved.entries())) all.append(entry(saved, e.getName()));
        check(!all.toString().contains("ISSUED_COOKIE_2026") && !all.toString().contains("STREAM_PRIVATE_2026") && !all.toString().contains("CUSTOM_QUERY_2026"), "saved archive hides discovered credentials in every text member");
        check(preview.contains(entry(saved, "responses/2.http")), "preview matches saved cross-packet redaction");
        var metadata = (Map<?, ?>) MinerJson.parse(entry(saved, "evidence.json"));
        var first = (Map<?, ?>) ((List<?>) metadata.get("items")).get(0);
        check(first.get("requestTextSha256").equals(Core.hash(request.getBytes(StandardCharsets.UTF_8))), "source text hash is calculated before redaction");
        check(first.get("responseExportTextSha256").equals(Core.hash(entry(saved, "responses/1.http").getBytes(StandardCharsets.UTF_8))), "export text hash matches exact saved text");
        check(first.get("responseExportBodyState").equals("REDACTED_JSON") && first.get("requestExportBodyState").equals("EMPTY"), "body analysis state stored per direction");
        check(Boolean.FALSE.equals(first.get("exportReplayable")) && first.get("method").equals("GET"), "request method retained while redacted export is not presented as replayable original");
        check(((List<?>) first.get("extraRedactedNames")).contains("customercode"), "custom redaction policy retained without its values");
      }
      BountyEvidence.save(zip, "title", "", "", items("{\"title\":\"ordinary\"}"), Set.of(), List.of(), false, "{}", null);
      try (ZipFile saved = new ZipFile(zip.toFile())) {
        check(entry(saved, "evidence.json").contains("OMITTED_BY_SETTING"), "body opt-out records explicit omission reason");
      }
      BountyEvidence.save(zip, "title", "", "", items("malformed JSON"), Set.of(), List.of(), true, "{}", null);
      try (ZipFile saved = new ZipFile(zip.toFile())) {
        check(entry(saved, "evidence.json").contains("OMITTED_UNSUPPORTED_OR_INVALID_BODY") && !entry(saved, "responses/1.http").contains("malformed JSON"), "invalid body excluded and reason retained");
      }
      String large = "{\"title\":\"" + "x".repeat(140000) + "\"}";
      BountyEvidence.save(zip, "title", "", "", items(large), Set.of(), List.of(), true, "{}", null);
      try (ZipFile saved = new ZipFile(zip.toFile())) {
        check(entry(saved, "evidence.json").contains("OMITTED_PREVIEW_LIMIT"), "preview truncation distinguished from invalid or excluded body");
      }
      byte[] before = Files.readAllBytes(zip);
      String excessive = "{\"token\":[" + String.join(",", java.util.stream.IntStream.range(0, 1050).mapToObj(i -> "\"distinct-secret-" + i + "\"").toList()) + "]}";
      try { BountyEvidence.save(zip, "title", "", "", items(excessive), Set.of(), List.of(), true, "{}", null); throw new AssertionError("unbounded redaction accepted"); }
      catch (IllegalArgumentException expected) { check(expected.getMessage().contains("가림 값 한도"), "redaction budget fails closed with actionable explanation"); }
      check(Arrays.equals(before, Files.readAllBytes(zip)), "redaction preflight failure preserves existing ZIP");
      try { BountyEvidence.save(zip, "title", "", "", items("{}"), Set.of(), List.of(), true, "{\"invalid\":", null); throw new AssertionError("invalid coverage exported"); }
      catch (IllegalArgumentException expected) { check(Arrays.equals(before, Files.readAllBytes(zip)), "invalid coverage fails closed without replacing previous evidence ZIP"); }
    } finally { Files.deleteIfExists(zip); }
    String jwt = Core.transform("Base64URL 인코딩", "{\"alg\":\"none\"}") + "." + Core.transform("Base64URL 인코딩", "{\"role\":\"admin\"}") + ".";
    String decoded = Core.jwt(jwt);
    check(decoded.contains("내용 해석:") && decoded.contains("서명 검증: 수행하지 않음") && decoded.contains("권한 효과: 미확인"), "JWT decoding, signature and authorization effect are separately reported");
    System.out.println("EvidenceHardeningTest: " + checks + " checks passed");
  }
}
