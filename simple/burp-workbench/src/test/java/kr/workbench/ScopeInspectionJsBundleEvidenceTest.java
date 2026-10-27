package kr.workbench;

import java.util.*;

/** Large-bundle tail discovery with exact call-argument positions and no guessed origin. */
public final class ScopeInspectionJsBundleEvidenceTest extends WorkbenchTest {
  public static void main(String[] args){
    String source="const padding='"+"x".repeat(JsAnalysis.SOURCE_LIMIT+32)+"';\n"
        +"class Service{login(e){return this.http.post(this.hostServer+`/rest/user/login`,e)}"
        +"find(t){return this.http.get(`${this.hostServer}/rest/basket/${t}`)}"
        +"search(t){return this.http.get(`${this.hostServer}/rest/products/search?q=${t}`)}}";
    var result=ScopeInspectionAnalysis.analyze("https://bundle.test/main.js",200,"Content-Type: application/javascript",source,"anonymous","bundle-1");
    var candidates=result.candidates().stream().filter(c->c.source().equals("JS API")).toList();
    for(String path:List.of("/rest/user/login","/rest/basket/{dynamic}","/rest/products/search")){
      var candidate=candidates.stream().filter(c->c.url().equals(path)).findFirst().orElseThrow(()->new AssertionError("missing "+path));
      check(!candidate.executable(),"unknown server/dynamic values remain a non-executable relative API: "+path);
      var finding=result.findings().stream().filter(f->f.category().equals("API")&&f.detail().contains(path)).findFirst().orElseThrow();
      check(finding.url().equals("https://bundle.test/main.js")&&finding.offset()>JsAnalysis.SOURCE_LIMIT&&finding.line()==2,"bundle-tail API evidence links to its original JS response and exact line: "+path);
      check(source.substring(finding.offset(),Math.min(source.length(),finding.offset()+100)).contains(path.equals("/rest/basket/{dynamic}")?"/rest/basket/${t}":path),"API source offset includes the actual HTTP call argument: "+path);
    }
    check(candidates.stream().anyMatch(c->c.url().equals("/rest/user/login")&&c.method().equals("POST")),"login preserves its POST method");
    check(!result.complete()&&result.note().contains("분할 분석"),"split or unsupported bundle analysis stays explicitly partial");
    check(candidates.stream().noneMatch(c->c.url().startsWith("https://bundle.test/rest/")),"an unknown API host is not invented from the script download host");
    var shell=ScopeInspectionAnalysis.analyze("https://bundle.test/app/",200,"Content-Type: text/html","<html><script src='/app/main.js'></script><form action='/app/unknown-action'></form></html>","anonymous","shell-1");
    check(shell.candidates().stream().anyMatch(c->c.url().equals("https://bundle.test/app/main.js")&&c.method().equals("GET")&&c.executable()),"an actual script src retains its observed GET resource evidence for a selected SPA shell");
    check(shell.candidates().stream().noneMatch(c->c.url().endsWith("/app/unknown-action")&&c.method().equals("GET")&&c.executable()),"retaining a script reference cannot invent GET for an unknown form method");
    var fake=ScopeInspectionAnalysis.analyze("https://bundle.test/app/",200,"Content-Type: text/html","<html><script>const markup=\"<script src='/app/fake.js'>\";</script></html>","anonymous","shell-2");
    check(fake.candidates().stream().noneMatch(c->c.url().endsWith("/app/fake.js")&&c.method().equals("GET")&&c.executable()),"a script tag written inside JavaScript text is not treated as an actual GET resource");
    System.out.println("ScopeInspectionJsBundleEvidenceTest: "+checks+" checks passed (large static bundle; no HTTP)");
  }
}
