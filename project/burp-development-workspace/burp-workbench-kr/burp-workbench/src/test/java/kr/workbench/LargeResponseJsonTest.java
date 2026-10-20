package kr.workbench;

import java.util.*;

/** Real large bodies and negative controls exercise the bounded structural traversal. */
public final class LargeResponseJsonTest {
  private static int checks;
  private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
  private static final String URL = "https://app.test/api/export";
  private static final String SECRET = "live_Z8aJ3cP7mQ2vL9wR4kT6";
  public static void main(String[] args) {
    String large = "{\"padding\":\"" + "x".repeat(PassiveFindings.BODY_LIMIT + 123) + "\",\"streamKey\":\"" + SECRET
        + "\",\"debug\":{\"systemDiagnostics\":{\"runtimeVersion\":\"test\",\"databaseHost\":\"private\"}}}";
    var result = PassiveFindings.analyzeDetailed(URL, 200, "Content-Type: application/json", large);
    check(result.findings().stream().anyMatch(f -> f.rule().equals("JSON 비밀 키 후보") && f.location().equals("JSON: /streamKey")), "secret after 1Mi prefix is found structurally");
    check(result.findings().stream().anyMatch(f -> f.rule().equals("JSON 진단정보 노출 후보")), "diagnostics after a large scalar are inspected");
    check(result.inspectedCharacters() == large.length() && !result.complete() && result.note().contains("일반 문자열 규칙"), "complete JSON traversal is not confused with complete coverage of all rules");
    check(!result.toString().contains(SECRET) && !result.toString().contains("private"), "evidence never retains secret or diagnostic values");
    var normal = ResponseJsonStream.inspect("{\"videoId\":\"" + SECRET + "\",\"clientId\":\"" + SECRET + "\",\"streamKey\":\"YOUR_API_KEY_HERE\"}", URL);
    check(normal.complete() && normal.findings().isEmpty(), "public identifiers and placeholders stay negative");
    var escaped = ResponseJsonStream.inspect("{\"a/b~c\":[{\"stream\\u004bey\":\"" + SECRET + "\"}]}", URL);
    check(escaped.complete() && escaped.findings().get(0).location().equals("JSON: /a~1b~0c/0/streamKey"), "escaped keys and array JSON pointer offsets remain exact");
    for (String invalid : List.of("{\"streamKey\":\"" + SECRET + "\",}", "{\"a\":01}", "{\"a\":1e}", "{\"a\":true}tail", "[1,]", "{\"a\":\"bad\\z\"}")) {
      var broken = ResponseJsonStream.inspect(invalid, URL);
      check(!broken.complete() && broken.findings().isEmpty(), "malformed JSON does not produce structural candidates");
    }
    check(!ResponseJsonStream.inspect("{\"a\":\"\\u００６１\"}", URL).complete(), "JSON unicode escapes require ASCII hex digits");
    var budget = ResponseJsonStream.inspect("{\"padding\":\"" + "x".repeat(ResponseJsonStream.CHARACTER_LIMIT) + "\",\"streamKey\":\"" + SECRET + "\"}", URL);
    check(!budget.complete() && budget.inspected() == ResponseJsonStream.CHARACTER_LIMIT && budget.findings().isEmpty(), "character budget reports unseen tail instead of claiming no secrets");
    var depth = ResponseJsonStream.inspect("[".repeat(70) + "0" + "]".repeat(70), URL);
    check(!depth.complete() && depth.note().contains("깊이"), "deep nested input stays bounded");
    var nodes = ResponseJsonStream.inspect("[" + "0,".repeat(ResponseJsonStream.NODE_LIMIT) + "0]", URL);
    check(!nodes.complete() && nodes.note().contains("항목"), "many small values cannot bypass node budget");
    check(ResponseJsonStream.inspect("{\"a\":[null,false,true,-12.3e+2,{},[]]}", URL).complete(), "all JSON scalar and container kinds are accepted");
    System.out.println("LargeResponseJsonTest: " + checks + " checks passed");
  }
}
