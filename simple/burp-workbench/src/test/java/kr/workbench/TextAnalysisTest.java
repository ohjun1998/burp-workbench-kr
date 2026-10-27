package kr.workbench;

import java.util.*;

/** Structured/text extraction preserves original offsets and never interprets XML entities. */
public final class TextAnalysisTest {
  static int checks;
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
  static JsAnalysis.Endpoint endpoint(JsAnalysis.Result r,String path){return r.endpoints().stream().filter(e->e.url().contains(path)).findFirst().orElseThrow(()->new AssertionError("missing "+path+": "+r));}
  public static void main(String[] args) {
    String key="A9b8C7d6E5f4G3h2I1j0K9l8";
    String json="{\n  \"api\":\"/v1/users?access_token=HIDDEN_QUERY\",\n  \"api_key\":\""+key+"\",\"links\":[\"https://api.example.test/orders\"],\"paths\":{\"/v1/items/{id}\":{}}}";
    var result=JsAnalysis.analyze(json,"https://example.test/config.json","application/json");
    check(result.status().contains("JSON")&&!result.status().contains("AST 실패"),"valid JSON is analyzed as data rather than failed JavaScript");
    var users=endpoint(result,"/v1/users");
    check(users.url().equals("https://example.test/v1/users")&&users.parameters().equals("access_token"),"JSON values resolve relative paths and retain only query names");
    check(users.line()==2&&users.start()==json.indexOf("\"/v1/users"),"JSON finding offsets and line numbers refer to the original text");
    check(endpoint(result,"api.example.test/orders").complete(),"JSON arrays retain absolute URL candidates");
    check(!endpoint(result,"/v1/items/{id}").complete(),"OpenAPI path keys expose unresolved path variables");
    check(users.method().equals("?"),"data strings do not invent an observed HTTP method");
    check(result.secrets().size()==1&&!result.toString().contains(key)&&!result.toString().contains("HIDDEN_QUERY"),"contextual JSON keys are masked and exports omit query values");
    String escaped="{\"endpoint\":\"https:\\/\\/example.test\\u002fescaped\",\"apiKey\":\"\\u0041Iza"+"Z".repeat(35)+"\"}";
    var decoded=JsAnalysis.analyze(escaped,"https://example.test/","application/problem+json");
    check(endpoint(decoded,"/escaped").url().equals("https://example.test/escaped"),"JSON unicode and escaped slashes are decoded without changing source positions");
    check(decoded.secrets().stream().anyMatch(s->s.rule().equals("Google API Key")),"provider patterns inspect decoded JSON strings");
    String xml="<?xml version='1.0'?><config>\n<endpoint>/xml/users?a=HIDDEN&amp;b=HIDDEN</endpoint>\n<apiKey>"+key+"</apiKey><link href='https://example.test/attrs'/><url><![CDATA[/xml/cdata]]></url></config>";
    var x=JsAnalysis.analyze(xml,"https://example.test/config.xml","application/xml");
    check(x.status().contains("XML")&&!x.status().contains("AST 실패"),"XML is analyzed without a JavaScript parser");
    check(endpoint(x,"/xml/users").parameters().equals("a, b")&&!x.toString().contains("HIDDEN"),"XML predefined entities preserve query names while excluding values");
    check(endpoint(x,"/attrs").kind().contains("href")&&endpoint(x,"/xml/cdata").complete(),"XML attributes and CDATA provide URL candidates");
    check(x.secrets().size()==1&&x.secrets().get(0).line()==3,"XML element names provide masked key candidates and original line positions");
    var entity=JsAnalysis.analyze("<!DOCTYPE c [<!ENTITY xxe SYSTEM 'file:///unreadable-private-canary'>]><c><url>&xxe;</url><endpoint>/safe</endpoint></c>","https://example.test/","text/xml");
    check(entity.endpoints().size()==1&&endpoint(entity,"/safe").url().equals("https://example.test/safe"),"external XML entities remain literal and are never read as paths");
    var text=JsAnalysis.analyze("api_url=https://example.test/plain?auth=HIDDEN\napi_key="+key+"\n/relative/path\nhttps://api.example.test/one\n", "https://example.test/notes.txt", "text/plain");
    check(text.endpoints().size()==3&&endpoint(text,"/relative/path").complete(),"plain text recognizes named URLs, absolute URLs and path lines without scheme duplicates");
    check(text.secrets().size()==1&&!text.toString().contains(key)&&!text.toString().contains("HIDDEN"),"plain configuration keys are masked");
    var html=JsAnalysis.analyze("<html><a href='/html/link'>go</a><script>fetch('/html/api')</script></html>","https://example.test/","text/html");
    check(endpoint(html,"/html/link").method().equals("?")&&endpoint(html,"/html/api").method().equals("GET"),"HTML combines attribute candidates with existing JavaScript AST methods");
    var inferred=JsAnalysis.analyze("{\"url\":\"/no-mime\"}","https://example.test/raw");
    check(endpoint(inferred,"/no-mime").complete(),"JSON is detected even without a MIME type or file extension");
    var yaml=JsAnalysis.analyze("endpoint: /yaml/api\napi_key: "+key,"https://example.test/settings.yml","application/yaml");
    check(endpoint(yaml,"/yaml/api").complete()&&yaml.secrets().size()==1,"YAML-style text provides endpoint and key candidates");
    check(!TextCandidates.accepts("image/png","")&&!TextCandidates.accepts("application/pdf","")&&TextCandidates.accepts("image/svg+xml",""),"binary media are excluded while SVG remains analyzable XML");
    check(TextCandidates.accepts("application/octet-stream","")&&TextCandidates.textual("<config>텍스트</config>")&&!TextCandidates.textual("PK\u0000\u0001binary"),"unknown content types require a textual body instead of executing or decoding binary data");
    String huge="{\"padding\":\""+"x".repeat(300000)+"\",\"url\":\"/after-long-string\"}";
    check(endpoint(JsAnalysis.analyze(huge,"https://example.test/","application/json"),"/after-long-string").complete(),"large JSON strings use bounded linear token scanning without a regex stack overflow");
    String many="["+String.join(",",Collections.nCopies(2200,"\"/api/item\""))+"]";
    var bounded=JsAnalysis.analyze(many,"https://example.test/","application/json");
    check(bounded.truncated()&&bounded.endpoints().size()<=JsAnalysis.RESULT_LIMIT,"large structured responses retain the existing explicit result budget");
    System.out.println("TextAnalysisTest: "+checks+" checks passed");
  }
}
