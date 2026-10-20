package kr.workbench;

import kr.payloadrunner.Engine;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/** Negative controls and report-derived failures; no external target or actual Burp host. */
public final class AssessmentHardeningTest extends WorkbenchTest {
  static List<Engine.Row> xss(UnaryOperator<String> output, String mime) throws Exception {
    var rows = new ArrayList<Engine.Row>();
    var config = new Engine.Config(Engine.Profile.XSS,Engine.Encoding.URL,
        "GET /?q="+Engine.MARK+" HTTP/1.1\r\nHost: app.test\r\n\r\n","baseline",Engine.defaults(Engine.Profile.XSS),0,50,"");
    Engine.run(config,request->{String raw=new String(request,StandardCharsets.ISO_8859_1);
      String query=raw.split("\\?q=",2)[1].split(" HTTP/",2)[0];
      String value=java.net.URLDecoder.decode(query,StandardCharsets.UTF_8);
      return ("HTTP/1.1 200 OK\r\nContent-Type: "+mime+"\r\n\r\n"+output.apply(value)).getBytes(StandardCharsets.UTF_8);
    },new AtomicBoolean(),rows::add);return rows;
  }
  static boolean candidate(List<Engine.Row> rows){return rows.stream().anyMatch(r->r.verdict().equals("XSS 반사 후보"));}
  static List<PassiveFindings.Finding> json(String body){return PassiveFindings.analyze("https://app.test/api/export",400,"Content-Type: application/json",body);}
  public static void main(String[] args) throws Exception {
    check(candidate(xss(v->"<html>"+v.split(" ")[0]+"</html>","text/html")),"whitespace-split HTML has an executable-context candidate");
    check(candidate(xss(v->"<html>"+(v.length()>1?v.substring(0,v.length()-1)+"Z":v)+"</html>","text/html")),"last-character replacement is covered by padded compact probes");
    check(candidate(xss(v->"<html>"+v+"</html>","text/html")),"unchanged active HTML stays a candidate");
    check(!candidate(xss(v->"<html>"+v.replace("<","&lt;").replace(">","&gt;")+"</html>","text/html")),"escaped text is not active HTML");
    check(!candidate(xss(v->"<!--"+v+"-->","text/html")),"HTML comments do not produce a candidate");
    check(!candidate(xss(v->"<script>const s="+MetadataExport.json(v)+";</script>","text/html")),"script string reflection cannot be called an event-handler candidate");
    check(!candidate(xss(v->"<template>"+v+"</template>","text/html")),"inert template content is excluded");
    check(!candidate(xss(v->"<div data-value="+MetadataExport.json(v.replace("\"","&quot;"))+">ok</div>","text/html")),"event-like text inside another quoted attribute is excluded");
    check(!candidate(xss(v->MetadataExport.json(Map.of("value",v)),"application/json")),"JSON reflection remains outside HTML XSS verdicts");
    var active=xss(v->v,"text/html");check(active.stream().allMatch(r->r.browserExecutionStatus().equals("NOT_RUN")),"engine never invents browser execution evidence");

    String diag="{\"error\":\"Invalid field names\",\"debug\":{\"systemDiagnostics\":{\"nodeVersion\":\"v22.0\",\"environment\":\"development\",\"database\":{\"connected\":true}}}}";
    check(json(diag).stream().anyMatch(f->f.location().equals("JSON: /debug/systemDiagnostics")),"diagnostic evidence retains the exact JSON pointer");
    for(String clean:List.of("{\"id\":1,\"email\":\"a@test\"}","{\"error\":\"invalid email\",\"field\":\"email\"}","{\"debug\":true}","{\"debug\":{\"systemDiagnostics\":{\"message\":\"invalid input\"}}}"))
      check(json(clean).stream().noneMatch(f->f.rule().contains("진단정보")),"ordinary data/validation errors are not diagnostic leaks: "+clean);
    String secret="live_8f3c1a9d-prod-ingest-key";
    var secrets=json("{\"streamKey\":\""+secret+"\",\"videoId\":\"mQJ6q1ZCzsg\"}");
    check(secrets.stream().filter(f->f.rule().equals("JSON 비밀 키 후보")).count()==1,"service-specific streamKey produces one candidate");
    check(secrets.toString().contains("/streamKey")&&!secrets.toString().contains(secret),"key evidence exposes path and masked length only");
    for(String value:List.of("","YOUR_API_KEY_HERE","************","https://app.test/token/abc123","REDACTED_VALUE"))
      check(json(MetadataExport.json(Map.of("streamKey",value))).stream().noneMatch(f->f.rule().contains("비밀")),"empty/placeholders/URLs are excluded from secret candidates");
    check(json(MetadataExport.json(Map.of("clientId",secret,"videoId",secret))).stream().noneMatch(f->f.rule().contains("비밀")),"public identifiers are not secret names");
    String tail="{\"padding\":\""+"x".repeat(70000)+"\",\"debug\":"+diag.substring(diag.indexOf("{\"systemDiagnostics"),diag.length()-1)+"}";
    var full=PassiveFindings.analyzeDetailed("https://app.test/api",400,"Content-Type: application/json",tail);
    check(full.complete()&&full.inspectedCharacters()==tail.length()&&full.findings().stream().anyMatch(f->f.rule().contains("진단정보")),"JSON structures after 64KiB are fully inspected");
    var limited=PassiveFindings.analyzeDetailed("https://app.test/api",200,"Content-Type: application/json","{\"p\":\""+"x".repeat(PassiveFindings.BODY_LIMIT)+"\"}");
    check(!limited.complete()&&limited.findings().stream().anyMatch(f->f.rule().contains("범위")),"bounded analysis cannot silently look complete");
    check(!PassiveFindings.analyzeDetailed("https://app.test/api",200,"Content-Type: application/json","{broken").complete(),"invalid JSON is visibly incomplete");
    var js=JsAnalysis.analyze("{\"streamKey\":\""+secret+"\"}","https://app.test/api/live/stream","application/json");
    check(js.secrets().stream().anyMatch(f->f.rule().contains("streamKey")),"JS/JSON analyzer shares the service-key naming rule");

    for(String message:List.of("This lesson explains SQL syntax and SELECT.","SQL syntax is invalid; please use the search form."))
      check(!SqlErrors.PATTERN.matcher(message).find(),"educational SQL wording alone is not a DB error");
    for(String error:List.of("SQLITE_ERROR: near SELECT","org.h2.jdbc.JdbcSQLSyntaxErrorException","org.postgresql.util.PSQLException: ERROR","SequelizeDatabaseError: query","You have an error in your SQL syntax near quote"))
      check(SqlErrors.PATTERN.matcher(error).find(),"vendor-specific DB evidence retained: "+error);
    var denied=AuthJudgement.rules("","$.error=Forbidden","");
    check(AuthJudgement.compare(200,"{\"data\":{\"id\":1}}",400,"{\"error\":\"Forbidden\"}",denied,"").kind()==AuthFinding.Kind.BLOCKED,"configured JSON denial explains 400");
    check(AuthJudgement.compare(200,"{\"data\":{\"id\":1}}",400,"{\"error\":\"Forbidden\",\"data\":{\"id\":1}}",denied,"").kind()==AuthFinding.Kind.DENIAL_DATA,"400 denial with protected data never becomes green");
    check(AuthJudgement.compare(200,"{\"data\":{\"id\":1}}",400,"{\"error\":\"validation failed\"}",denied,"").kind()==AuthFinding.Kind.REVIEW,"unrecognized 400 remains review");
    var missing=EnvironmentContent.inspect("https://app.test/missing",404,"Content-Type: text/html","x".repeat(70000),"");
    check(missing.kind().equals("MISSING")&&missing.reason().contains("70000")&&missing.title().contains("미검사"),"large 404 preserves status and explicitly limits content inspection");

    String large="const baseURL='https://api.test/v2';"+";".repeat(JsAnalysis.SOURCE_LIMIT)+"fetch(baseURL+'/orders',{method:'POST'});";
    var batch=JsBatchAnalysis.analyze(large,"https://cdn.test/app.js","application/javascript","https://app.test/");
    var endpoint=batch.endpoints().stream().filter(e->e.url().equals("https://api.test/v2/orders")&&e.method().equals("POST")).findFirst();
    check(endpoint.isPresent()&&endpoint.get().start()==large.indexOf("baseURL+"),"cross-unit immutable baseURL keeps exact path/method/offset");
    check(batch.truncated()&&batch.status().contains("문맥 미확정"),"split analysis explicitly limits non-constant context");
    String regex="const r=/[;{}]/;"+";".repeat(JsAnalysis.SOURCE_LIMIT)+"fetch('/tail');";
    check(JsBatchAnalysis.cut(regex,0).safe(),"regex punctuation cannot change statement depth");
    String huge="function f(){const s='"+"x".repeat(4*1024*1024)+"';fetch('/end');}";
    check(!JsBatchAnalysis.cut(huge,0).safe(),"forced cut within a huge string cannot claim a safe context");

    String xml="<?xml version=\"1.0\"?><transaction><account>owned-test</account><description>hello</description><amount>1</amount></transaction>";
    List<Engine.Row> xmlRows=new ArrayList<>();List<String> sentXml=new ArrayList<>();
    var xmlConfig=new Engine.Config(Engine.Profile.XXE,Engine.Encoding.RAW,"POST /xml HTTP/1.1\r\nHost: app.test\r\nContent-Type: application/xml\r\n\r\n"+Engine.MARK,xml,List.of(Engine.defaults(Engine.Profile.XXE).get(0)),0,5,"");
    Engine.run(xmlConfig,request->{String body=new String(request,StandardCharsets.ISO_8859_1).split("\r\n\r\n",2)[1];sentXml.add(body);
      var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
      var doc=factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(body)));
      String result=doc.getElementsByTagName("description").item(0).getTextContent();
      return ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n"+MetadataExport.json(Map.of("description",result))).getBytes(StandardCharsets.UTF_8);
    },new AtomicBoolean(),xmlRows::add);
    check(sentXml.get(1).contains("<transaction>")&&sentXml.get(1).contains("<account>owned-test</account>")&&sentXml.get(1).contains("<amount>1</amount>"),"default XML probe retains root and unrelated business fields");
    check(sentXml.get(1).contains("<description>&pr;</description>")&&sentXml.get(1).startsWith("<?xml version=\"1.0\"?><!DOCTYPE transaction"),"entity probe targets the original description after the XML declaration");
    check(xmlRows.stream().anyMatch(row->row.verdict().equals("XML 엔티티 확장 관찰")&&row.evidence().contains("확정 근거 아님")),"business XML entity expansion remains distinct from confirmed external XXE");
    System.out.println("AssessmentHardeningTest: "+checks+" checks passed");
  }
}
