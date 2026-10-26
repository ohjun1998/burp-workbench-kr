package kr.workbench;

import java.util.List;

public final class ResponseDifferenceTest {
  static int checks;
  static void check(boolean ok, String name) {
    if (!ok) throw new AssertionError(name);
    checks++; System.out.println("PASS " + name);
  }
  static ResponseDifference.Item item(ResponseDifference.Summary s, String path) {
    return s.items().stream().filter(i -> i.path().equals(path)).findFirst()
        .orElseThrow(() -> new AssertionError("Missing field " + path));
  }
  static String slice(String raw, ResponseDifference.Span span) {
    return span.present() ? raw.substring(span.start(), span.end()) : null;
  }
  public static void main(String[] args) {
    String a = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\nX-Remove: old\r\n\r\n"
        + "{\"user\":{\"role\":\"reader\",\"id\":9007199254740993},\"array\":[true,null,1],\"old\":false}";
    String b = "HTTP/1.1 403 Forbidden\r\ncontent-type: application/json\r\nSet-Cookie: a=1\r\nSet-Cookie: b=3\r\nX-New: new\r\n\r\n"
        + "{\"user\":{\"role\":\"writer\",\"id\":9007199254740994},\"array\":[false,null,\"1\"],\"added\":true}";
    var s = ResponseDifference.compare(a, b);
    check(s.items().size() == 10, "status headers nested JSON and array changes counted separately");
    check(!s.limited(), "ordinary responses remain complete");
    check(item(s, "HTTP").category().equals("상태 줄"), "HTTP status line appears first");
    check(item(s, "set-cookie[2]").left().equals("b=2") && item(s, "set-cookie[2]").right().equals("b=3"),
        "duplicate header occurrences retain exact pairing");
    check(s.items().stream().noneMatch(i -> i.path().equals("content-type[1]")), "header name case is normalized explicitly");
    check(item(s, "x-remove[1]").change().equals("제거") && !item(s, "x-remove[1]").rightSpan().present(), "header removal has no fabricated right location");
    check(item(s, "x-new[1]").change().equals("추가") && !item(s, "x-new[1]").leftSpan().present(), "header addition has no fabricated left location");
    check(item(s, "$.user.id").left().equals("9007199254740993"), "large integers keep exact decimal digits");
    check(item(s, "$.user.id").right().equals("9007199254740994"), "adjacent large integers do not collapse");
    check(item(s, "$.array[2]").left().equals("1") && item(s, "$.array[2]").right().equals("\"1\""),
        "number to string type change remains visible");
    check(slice(a, item(s, "$.user.role").leftSpan()).equals("\"reader\""), "nested left raw span targets exact original value");
    check(slice(b, item(s, "$.user.role").rightSpan()).equals("\"writer\""), "nested right raw span targets exact original value");
    check(slice(a, item(s, "set-cookie[2]").leftSpan()).equals("Set-Cookie: b=2"), "header raw span includes unmodified original line");
    check(s.left().equals(a) && s.right().equals(b), "summary never rewrites original inputs");
    check(s.note().contains("제외 필드 없음") && s.note().contains("취약점 확정 아님"), "exclusions and inference limit stay explicit");

    var escaped = ResponseDifference.compare("{\"a.b\":{\"q'\\\\x\":\"\\u0061\"}}", "{\"a.b\":{\"q'\\\\x\":\"b\"}}");
    check(escaped.items().size() == 1 && escaped.items().get(0).path().equals("$['a.b']['q\\'\\\\x']"), "JSONPath escapes ambiguous property names");
    check(slice(escaped.left(), escaped.items().get(0).leftSpan()).equals("\"\\u0061\""), "escaped string span points at original spelling");
    check(ResponseDifference.compare("{\"value\":\"\\u0061\"}", "{\"value\":\"a\"}").items().isEmpty(), "equivalent string escapes do not invent value changes");
    check(ResponseDifference.compare("{\"value\":1}", "{\"value\":1.0}").items().size() == 1, "numeric representation changes are explicitly included");
    check(ResponseDifference.compare("1e99999999", "1e99999998").items().size() == 1, "very large exponent stays bounded lexical data");
    check(ResponseDifference.compare("null", "null").items().isEmpty(), "JSON null equality has no change");
    check(item(ResponseDifference.compare("{\"v\":null}", "{}"), "$.v").change().equals("제거"), "null is distinguished from absent");
    check(item(ResponseDifference.compare("{}", "{\"v\":null}"), "$.v").right().equals("null"), "added null remains a real value");
    check(item(ResponseDifference.compare("[]", "[{},[]]"), "$[0]").right().equals("{}"), "added object subtree remains one inspectable field");
    check(item(ResponseDifference.compare("{\"v\":{}}", "{\"v\":[]}"), "$.v").right().equals("[]"), "container type change remains visible");
    check(ResponseDifference.compare("{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1}").items().isEmpty(), "object ordering is left to raw line comparison");

    for (String invalid : List.of("{\"a\":1,\"a\":2}", "{\"a\":", "[1,]", "{\"a\":\"\\u+123\"}", "{\"a\":01}")) {
      var invalidSummary = ResponseDifference.compare(invalid, "{\"a\":2}");
      check(invalidSummary.items().isEmpty(), "invalid JSON falls back without false missing fields: " + invalid);
      check(invalidSummary.note().contains("JSON 항목 요약 생략"), "invalid JSON fallback is explained");
    }
    var duplicate = ResponseDifference.compare("HTTP/1.1 200 OK\nX-A: 1\n\n{\"a\":1,\"a\":2}",
        "HTTP/1.1 403 Nope\nX-A: 2\n\n{\"a\":3}");
    check(duplicate.items().size() == 2 && duplicate.note().contains("중복 키"), "JSON duplicate keys do not hide independently valid status and header changes");
    var xml = ResponseDifference.compare("HTTP/1.1 200 OK\n\n<x>1</x>", "HTTP/1.1 200 OK\n\n<x>2</x>");
    check(xml.items().isEmpty() && xml.note().contains("JSON 본문이 아닙니다"), "XML is explicitly left to unchanged bounded line comparison");
    var malformed = ResponseDifference.compare("HTTP/1.1 200 OK\ninvalid header\n\n{}", "HTTP/1.1 200 OK\n\n{}");
    check(malformed.note().contains("구조화하지 못한 헤더"), "malformed headers are disclosed rather than silently ignored");
    check(ResponseDifference.compare("HTTP/1.1 200 OK", "HTTP/1.1 200 OK").note().contains("구분 줄"), "missing HTTP body delimiter is explained");
    check(ResponseDifference.compare("HTTP/1.1 200 OK\n\n{\"a\":1}", "{\"a\":2}").note().contains("자료 형식이 다릅니다"), "mixed response and JSON inputs are disclosed");

    var longValue = ResponseDifference.compare("{\"v\":\"" + "x".repeat(70_000) + "\"}", "{\"v\":\"y\"}");
    check(longValue.limited() && longValue.left().length() == 65_536, "input is bounded before parsing and original detail display");
    check(longValue.note().contains("전체 차이 수가 아닙니다"), "input truncation cannot be mistaken for full comparison");
    String deep = "[".repeat(34) + "0" + "]".repeat(34);
    var deepSummary = ResponseDifference.compare(deep, "[]");
    check(deepSummary.limited() && deepSummary.note().contains("구조 제한"), "deep structures fail closed without stack growth");
    StringBuilder left = new StringBuilder("{"), right = new StringBuilder("{");
    for (int i = 0; i < 300; i++) {
      if (i > 0) { left.append(','); right.append(','); }
      left.append('"').append(i).append("\":0"); right.append('"').append(i).append("\":1");
    }
    left.append('}'); right.append('}');
    var many = ResponseDifference.compare(left.toString(), right.toString());
    check(many.items().size() == 256 && many.limited(), "summary item count is bounded and marked partial");
    String nodes = "[" + "0,".repeat(4_096) + "0]";
    check(ResponseDifference.compare(nodes, "[]").limited(), "node budget rejects oversized semantic trees");
    var manyHeaders = ResponseDifference.compare("HTTP/1.1 200 OK\n" + "X-A: value\n".repeat(257) + "\n{}", "HTTP/1.1 200 OK\n\n{}");
    check(manyHeaders.limited(), "header occurrence storage is bounded");
    var preview = ResponseDifference.compare("{\"v\":\"" + "x".repeat(500) + "\"}", "{\"v\":\"y\"}");
    check(item(preview, "$.v").left().length() <= 163 && item(preview, "$.v").left().endsWith("…"), "table previews remain bounded with truncation marker");
    check(slice(preview.left(), item(preview, "$.v").leftSpan()).length() == 502, "preview truncation never corrupts raw location span");
    System.out.println("Response field differences: " + checks + " checks passed");
  }
}
