package kr.workbench;

import java.util.*;
import java.util.concurrent.TimeUnit;
import kr.payloadrunner.Engine;

/** Negative controls for the observations missed by the third-party application run. */
public final class OssLogicUpgradeTest extends WorkbenchTest {
  private static AuthJudgement.Rules rules() { return AuthJudgement.rules("", "", ""); }
  private static JsAnalysis.Endpoint path(JsAnalysis.Result result, String fragment, String method) {
    return result.endpoints().stream().filter(e -> e.url().contains(fragment) && e.method().equals(method))
        .findFirst().orElseThrow(() -> new AssertionError(result));
  }
  public static void main(String[] args) throws Exception {
    String reference = "{\"user\":{\"id\":1,\"email\":\"a@example.test\"}}";
    for (String empty : List.of("{}", "[]", "{\"user\":{}}", "{\"user\":null}",
        "{\"user\":{\"id\":null,\"email\":\"\"}}", "{\"data\":[],\"status\":\"success\"}")) {
      var verdict = AuthJudgement.compare(200, reference, 200, empty, rules(), "");
      check(verdict.kind() == AuthFinding.Kind.INCOMPLETE, "empty successful payload cannot establish an authorization bypass: " + empty);
    }
    check(!AuthResponseData.emptyPayload(AuthJudgement.json("{\"user\":{\"id\":0,\"active\":false}}")), "zero and false remain material application values");
    check(!AuthResponseData.emptyPayload(AuthJudgement.json("{\"data\":{\"message\":\"private content\"}}")), "metadata-like names inside a payload remain material data");
    check(!AuthResponseData.emptyPayload(AuthJudgement.json("{\"ok\":true}")), "a status-only operation without an empty envelope keeps existing handling");
    check(AuthJudgement.compare(200, reference, 200, reference, rules(), "").kind() == AuthFinding.Kind.SUSPECT, "same material user data remains an access candidate");

    var denial = AuthResponseRules.parse("BODY: Malicious activity detected.");
    for (int status : List.of(200, 400, 422)) {
      var result = denial.assess(200, "", reference, status, "", "{\"status\":\"error\",\"data\":\"Malicious activity detected.\"}");
      check(result.kind() == AuthFinding.Kind.BLOCKED, "explicit complete denial message explains HTTP " + status);
    }
    check(denial.assess(200, "", reference, 400, "", "{\"data\":{\"id\":2},\"message\":\"Malicious activity detected.\"}").kind() == AuthFinding.Kind.DENIAL_DATA, "400 with real data cannot become a green denial");
    check(denial.assess(200, "", reference, 400, "", "{\"data\":{\"message\":\"private content\"},\"error\":\"Malicious activity detected.\"}").kind() == AuthFinding.Kind.DENIAL_DATA, "nested message fields cannot conceal returned content behind a denial");
    check(denial.assess(200, "", reference, 200, "", "{\"data\":\"private Malicious activity detected.\"}").kind() == AuthFinding.Kind.DENIAL_DATA, "partial denial substring does not conceal returned data");
    check(denial.assess(200, "", reference, 400, "", "<p>Malicious activity detected.</p><p>private</p>").kind() == AuthFinding.Kind.REVIEW, "unstructured 400 remains review when only part of the body matches");
    for (int status : List.of(302, 404, 410, 429, 500, 503))
      check(denial.assess(200, "", reference, status, "", "Malicious activity detected.") == null, "denial rule never promotes unrelated status " + status);
    var important = new AuthJudgement.Rules(Set.of(), List.of(), "", AuthPolicy.Access.UNSPECIFIED, Set.of("$.data"), false);
    check(denial.assess(200, "", reference, 400, "", "{\"data\":\"Malicious activity detected.\"}", important).kind() == AuthFinding.Kind.DENIAL_DATA, "explicit important data field takes priority over denial scalar exemption");

    String source = "class Service { #x=0; login(e){ return this.http.post(this.hostServer+`/rest/user/login`,e) } "
        + "basket(id){return this.http.get(`${this.hostServer}/rest/basket/${id}`)} "
        + "search(q){return this.http.get(`${this.hostServer}/rest/products/search?q=${q}`)} }";
    var found = JsBatchAnalysis.analyze(source, "https://cdn.test/main.js", "application/javascript", "https://app.test/");
    var login = path(found, "/rest/user/login", "POST");
    var basket = path(found, "/rest/basket/", "GET");
    var search = path(found, "/rest/products/search", "GET");
    check(!login.complete() && login.url().equals("/rest/user/login"), "unknown base variable never becomes the document or CDN host");
    check(basket.url().contains("{dynamic}") && !basket.complete(), "dynamic object id survives as an unresolved path placeholder");
    check(search.parameters().contains("q") && !search.url().contains("${"), "template query keeps the parameter name without expression or value");
    check(login.start() == source.indexOf("this.hostServer+"), "recovered call retains exact original argument offset");
    check(found.requestDrafts().get(JsAnalysis.draftKey(login)).url().contains("REPLACE-HOST.invalid"), "unknown origin draft stays visibly blocked");
    check(found.requestDrafts().get(JsAnalysis.draftKey(login)).body().equals("REPLACE_BODY"), "unparsed write body is not silently replaced with an empty body");
    String constants = "const id=7; const url='/orders'; http.post(url,{id:id,quantity:2});";
    var precise = JsAnalysis.analyzeContext(constants, "https://cdn.test/app.js", "application/javascript", "https://app.test/");
    var order = path(precise, "/orders", "POST");
    check(order.url().equals("https://app.test/orders") && order.complete(), "supported HttpClient AST uses observed document context");
    check(precise.requestDrafts().get(JsAnalysis.draftKey(order)).body().contains("\"quantity\":2"), "AST-resolved write body takes precedence over lexical placeholders");
    var noise = JsHttpCalls.discover("// http.get('/comment')\nconst s=\"http.post('/string')\"; const r=/http.get('regex')/; map.get('/map');", "https://app.test/", 100, System.nanoTime()+TimeUnit.SECONDS.toNanos(3));
    check(noise.isEmpty(), "comments, strings, regexes and unrelated Map.get calls cannot fabricate HTTP endpoints");
    String tail = "const n=0;".repeat(22000) + "http.get('/after-token-cap');";
    check(JsHttpCalls.discover(tail, "https://app.test/", 100, System.nanoTime()+TimeUnit.SECONDS.toNanos(4)).stream().anyMatch(e -> e.url().endsWith("/after-token-cap")), "streaming recovery reaches calls beyond the old 100000-token list");
    var unknown = JsHttpCalls.discover("http.get('/relative');", null, 100, System.nanoTime()+TimeUnit.SECONDS.toNanos(2));
    check(!unknown.get(0).complete() && unknown.get(0).url().equals("/relative"), "missing document context never invents an origin");
    var retained = JsAnalysisPanel.partial(precise, "metadata failure");
    check(retained.endpoints().equals(precise.endpoints()) && retained.requestDrafts().equals(precise.requestDrafts()), "metadata failure preserves recovered calls and mapped request drafts");
    var malformed = JsBatchAnalysis.analyze("class X { #private=1; } const fragment='/g,`&quot;`).replace(/'; http.get('/valid');", "https://cdn.test/main.js", "application/javascript", null);
    check(malformed.endpoints().stream().anyMatch(e -> e.url().equals("/valid") && e.method().equals("GET")), "malformed fallback URL without a document cannot erase valid recovered HTTP calls");
    check(malformed.endpoints().stream().noneMatch(e -> e.url().contains("&quot;")), "non-URL syntax fragment is omitted rather than crashing the bundle");

    String sqlite = "{\"error\":\"SQLITE_ERROR: near syntax\"}";
    check(SqlErrors.PATTERN.matcher(sqlite).find(), "shared SQL observation recognizes the real SQLite error code");
    check(SqlErrors.PATTERN.matcher("SequelizeDatabaseError: SQLITE_ERROR").find(), "ORM database error wrapper is recognized");
    check(PassiveFindings.analyze("https://app.test/search",500,"Content-Type: application/json",sqlite).stream().anyMatch(f -> f.rule().contains("오류")), "passive response observation includes SQLite implementation errors");
    var config = new Engine.Config(Engine.Profile.SQLI,Engine.Encoding.URL,"GET /?q="+Engine.MARK+" HTTP/1.1\r\nHost: app.test\r\n\r\n","normal",List.of("\""),0,4,"");
    byte[] baseline = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{}".getBytes();
    byte[] error = ("HTTP/1.1 500 Error\r\nContent-Type: application/json\r\n\r\n"+sqlite).getBytes();
    List<Engine.Row> rows=new ArrayList<>();int[] sends={0};
    Engine.run(config,request->++sends[0]<=2?baseline:error,new java.util.concurrent.atomic.AtomicBoolean(),rows::add);
    check(rows.stream().anyMatch(row->row.verdict().equals("SQL 오류 후보")), "active SQL runner uses the same SQLite evidence pattern");
    rows.clear();Engine.run(config,request->error,new java.util.concurrent.atomic.AtomicBoolean(),rows::add);
    check(rows.stream().noneMatch(row->row.verdict().equals("SQL 오류 후보")), "pre-existing baseline SQLite error is not fresh SQL injection evidence");
    check(Engine.defaults(Engine.Profile.SQLI).stream().anyMatch(v -> v.contains("%'))") && v.contains("1=1")), "default candidates include paired quoted-LIKE bracket contexts");

    String marker = "WB_REDIRECT_20261018";
    var flows = new StoredFlows(); flows.register(marker);
    flows.observe("https://app.test/profile","POST",marker,302,"text/html","","/profile");
    check(flows.views().size()==1 && !flows.views().get(0).writeAccepted(), "302 remains pending rather than being called write success");
    flows.observe("https://app.test/profile","GET","",200,"text/html",marker,"");
    var linked = flows.views().get(flows.views().size()-1);
    check(linked.readbackObserved() && !linked.writeAccepted() && linked.writeSource().status()==302, "successful observed redirect target links the exact original 302 write");
    check(linked.htmlObserved() && linked.browserEvidence().isEmpty(), "redirect readback still cannot claim browser execution");
    flows.observe("https://app.test/profile","POST",marker,303,"text/html","","https://outside.test/profile");
    flows.observe("https://app.test/profile","GET","",200,"text/html",marker,"");
    check(flows.views().size()==1 && !flows.views().get(0).readbackObserved(), "foreign redirect never establishes an automatic stored chain");
    flows.observe("https://app.test/profile","POST",marker,302,"text/html","","/result");
    flows.observe("https://app.test/unrelated","GET","",200,"text/html",marker,"");
    check(flows.views().size()==1, "different readback path cannot satisfy a pending redirect");
    flows.observe("https://app.test/result?echo="+marker,"GET","",200,"text/html",marker,"");
    check(flows.views().size()==1, "query reflection of the marker cannot satisfy redirect storage evidence");
    flows.observe("https://app.test/result","GET","",302,"text/html","","/final");
    flows.observe("https://app.test/final","GET","",200,"text/html",marker,"");
    check(flows.views().stream().anyMatch(StoredFlows.Flow::readbackObserved), "already observed same-origin redirect chain can reach its final readback");
    flows.observe("https://app.test/profile","POST",marker,403,"text/html","","");
    flows.observe("https://app.test/final","GET","",200,"text/html",marker,"");
    check(flows.views().size()==1 && !flows.views().get(0).readbackObserved(), "failed later write invalidates previous redirect evidence");
    System.out.println("OssLogicUpgradeTest: "+checks+" checks passed");
  }
}
