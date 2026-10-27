package kr.workbench;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

/** Exact response-only values, coordinate provenance, safe local rendering and atomic publication. */
public final class ScopeInspectionReportTest extends WorkbenchTest {
  static DiskTrafficStore.PacketRef packet(DiskTrafficStore store,String url,String type,String body,List<HttpHeader> extra)throws Exception{
    var request=request(url,"GET",true,List.of(header("Authorization","Bearer REQUEST_AUTH_ONLY"),header("Cookie","SID=REQUEST_COOKIE_ONLY")));
    List<HttpHeader>headers=new ArrayList<>();headers.add(header("Content-Type",type));headers.addAll(extra);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream raw=new ByteArrayOutputStream();raw.writeBytes(("HTTP/1.1 200 OK\r\n"+String.join("\r\n",headers.stream().map(HttpHeader::toString).toList())+"\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));raw.writeBytes(bytes);
    byte[] wire=raw.toByteArray();HttpResponse response=proxy(HttpResponse.class,(p,m,a)->switch(m.getName()){
      case "statusCode"->(short)200;case "headers"->headers;case "body"->bytes(bytes);case "bodyToString"->body;case "toByteArray"->bytes(wire);case "toString"->new String(wire,StandardCharsets.UTF_8);
      case "headerValue"->headers.stream().filter(h->h.name().equalsIgnoreCase(a[0].toString())).map(HttpHeader::value).findFirst().orElse(null);default->def(m);
    });registerTrafficPacket(request,response);return store.append("report-test","isolated exact-value fixture",request,response);
  }
  static ScopeInspection.Page page(DiskTrafficStore.PacketRef packet){return new ScopeInspection.Page(packet.url(),"GET","선택 계정",200,"JSON",true,"", "a".repeat(64),packet,"r2-auth-revision");}
  static ScopeInspection.Snapshot snapshot(List<ScopeInspection.Page>pages,List<ScopeInspection.Finding>findings){return new ScopeInspection.Snapshot("report-run",ScopeInspection.State.COMPLETED,"",pages.size(),pages.size(),0,0,0,0,findings,pages,List.of(),10,20);}
  static Map<String,String> zip(Path file)throws IOException{Map<String,String>out=new HashMap<>();try(ZipInputStream input=new ZipInputStream(Files.newInputStream(file),StandardCharsets.UTF_8)){ZipEntry entry;while((entry=input.getNextEntry())!=null)out.put(entry.getName(),new String(input.readAllBytes(),StandardCharsets.UTF_8));}return out;}
  static void ioFailure(Io action,String message)throws Exception{try{action.run();throw new AssertionError(message);}catch(IOException expected){check(true,message);}}
  interface Io{void run()throws Exception;}
  public static void main(String[]args)throws Exception{
    Path directory=Files.createTempDirectory("scope-raw-report-test-");
    try(DiskTrafficStore store=new DiskTrafficStore()){
      String apiKey="REAL_REPORT_KEY_A7b2C9d4E1f8G5h0";
      String token="eyJhbGciOiJub25lIn0.eyJzdWIiOiJsb2NhbCJ9.signature";
      String multiline="first line\n<svg onload=alert(1)>\n"+"LONG_VALUE_".repeat(1000);
      String body="{\n\"apiKey\":\""+apiKey+"\",\"accessToken\":\""+token+"\",\"publicapikey\":\"PUBLIC_CLIENT_VALUE\","
          +"\"demoToken\":\"LAB_ONLY_DEMO_TOKEN_NOT_A_REAL_SECRET\",\"password\":\"p\\u0040ss\\nword\","
          +"\"nested\":{\"access/token\":\"NESTED_TOKEN_VALUE\",\"dbHost\":\"db.internal\",\"databaseUrl\":\"jdbc:postgresql://dbuser:dbpass@db.internal/app\",\"runtimeVersion\":\"node 20.11\",\"userId\":12345678901234567890.000},"
          +"\"apiUrl\":\"/rest/user/login?return=ACTUAL_QUERY\",\"secretMultiline\":"+MetadataExport.json(multiline)+",\"secretFormula\":\"=HYPERLINK(\\\"http://example.invalid\\\",1)\"}";
      var json=packet(store,"https://report.test/config?token=REQUEST_URL_ONLY","application/json",body,
          List.of(header("Server","nginx/1.24.0"),header("Set-Cookie","SID=RESPONSE_COOKIE_ACTUAL; HttpOnly; Secure"),header("Authorization","Bearer RESPONSE_BEARER_ACTUAL")));
      String js="const api_key='JS_LITERAL_ACTUAL_123'; const latestToken='LATEST_REAL_456'; const secret=process.env.SECRET; const apiKeyFn=getKey(); const passwordJoin='PARTIAL_LITERAL_'+readSecret(); axios.post('/rest/basket/add?real=QUERY_VALUE',{});";
      var script=packet(store,"https://report.test/app.js","application/javascript",js,List.of());
      String markup="<html><input name='access_token' value='HTML_TOKEN_ACTUAL'><a href='/api/items?x=1&amp;y=2'>API</a><script>const publicApiKey='HTML_PUBLIC_VALUE';</script></html>";
      var html=packet(store,"https://report.test/app","text/html",markup,List.of());
      String xml="<config><password>XML_ACTUAL_PASSWORD</password><apiUrl>/api/xml?x=1&amp;y=2</apiUrl></config>";
      var xmlPacket=packet(store,"https://report.test/config.xml","application/xml",xml,List.of());
      String data="API_KEY=q7VnPk2x.A8tRz9Lm\nTOKEN=${DEPLOY_TOKEN}\n";
      var envPacket=packet(store,"https://report.test/config.env","text/plain",data,List.of());
      List<ScopeInspection.Finding> findings=List.of(
          new ScopeInspection.Finding("SECRET","키 후보",ScopeInspectionAnalysis.safeUrl(json.url()),"GET","선택 계정","형식 후보","중간","미검증","",json.id(),2,body.indexOf(apiKey),ScopeInspectionAnalysis.hash(apiKey).substring(0,16)),
          new ScopeInspection.Finding("API","관계 없는 URL 후보",ScopeInspectionAnalysis.safeUrl(json.url()),"GET","선택 계정","추정","정보","미검증","",json.id(),2,body.indexOf("p\\u0040ss"),"b".repeat(64)));
      var snapshot=snapshot(List.of(page(json),page(script),page(html),page(xmlPacket),page(envPacket)),findings);
      var document=ScopeInspectionReport.collect(snapshot);
      check(document.values().stream().anyMatch(v->v.category().equals("KEY")&&v.value().equals(apiKey)&&v.provenance().equals("PRODUCT_FINDING")),"exact key value links to the existing product finding fingerprint");
      check(document.values().stream().anyMatch(v->v.name().equals("password")&&v.value().equals("p@ss\nword")&&v.rawText().equals("\"p\\u0040ss\\nword\"")&&v.provenance().equals("REPORT_SUPPLEMENT")),"JSON parsed value and exact escaped source are distinct and unrelated API offsets do not imply product detection");
      check(document.values().stream().anyMatch(v->v.category().equals("TOKEN")&&v.value().equals(token)),"token category retains the actual response token without provider validation");
      check(document.values().stream().anyMatch(v->v.value().equals("PUBLIC_CLIENT_VALUE")&&v.classification().equals("PUBLIC_IDENTIFIER")),"publicapikey uses the shared public-identifier policy");
      check(document.values().stream().anyMatch(v->v.value().equals("LAB_ONLY_DEMO_TOKEN_NOT_A_REAL_SECRET")&&v.classification().equals("EXAMPLE_OR_PLACEHOLDER")),"explicit demo token remains an example rather than an inconsistent live-secret claim");
      check(document.values().stream().anyMatch(v->v.value().equals("LATEST_REAL_456")&&v.classification().equals("UNVERIFIED")),"latestToken is not downgraded because test is a substring of latest");
      check(document.values().stream().noneMatch(v->v.value().equals("process.env.SECRET")||v.value().equals("getKey()")||v.name().equals("passwordJoin")),"code expressions and concatenated literal fragments are excluded from actual values");
      check(document.values().stream().anyMatch(v->v.value().equals("q7VnPk2x.A8tRz9Lm")&&v.classification().equals("UNVERIFIED")),"opaque dotted data-file value is not mistaken for a JavaScript property expression");
      check(document.pages().stream().anyMatch(p->p.note().contains("코드 식")),"excluded expressions remain explicit in the response parsing notes");
      check(document.values().stream().anyMatch(v->v.value().equals("12345678901234567890.000")&&v.valueType().equals("NUMBER")),"large numeric JSON value preserves its original lexical precision");
      check(document.values().stream().anyMatch(v->v.value().equals("NESTED_TOKEN_VALUE")&&v.source().locator().equals("JSON:/nested/access~1token")),"nested JSON source uses a correct escaped JSON pointer");
      check(document.values().stream().anyMatch(v->v.value().equals(multiline)&&v.rawText().length()>10000),"long multiline value is preserved without preview clipping");
      check(document.values().stream().anyMatch(v->v.value().equals("RESPONSE_COOKIE_ACTUAL")&&v.source().area().equals("RESPONSE_HEADERS")),"response cookie actual value is extracted separately from its attributes");
      check(document.values().stream().anyMatch(v->v.value().equals("RESPONSE_BEARER_ACTUAL")&&v.category().equals("TOKEN")),"response authorization token is distinct from request profile credentials");
      check(document.values().stream().anyMatch(v->v.value().equals("HTML_TOKEN_ACTUAL"))&&document.values().stream().anyMatch(v->v.value().equals("XML_ACTUAL_PASSWORD")),"HTML paired input fields and XML scalar text are parsed from actual response positions");
      var api=document.values().stream().filter(v->v.value().equals("/rest/basket/add?real=QUERY_VALUE")).findFirst().orElseThrow();
      check(api.category().equals("API")&&api.attributes().get("httpMethod").equals("POST")&&api.attributes().get("resolvedEndpoint").equals("https://report.test/rest/basket/add?real=QUERY_VALUE")&&api.rawText().startsWith("'"),"observed API literal, actual method and reconstructed endpoint are kept separate");
      Map<String,String> sources=Map.of(json.id(),body,script.id(),js,html.id(),markup,xmlPacket.id(),xml,envPacket.id(),data);
      for(var value:document.values())if(value.source().area().equals("DECODED_RESPONSE_BODY")){
        var source=value.source();check(value.rawText().equals(sources.get(source.originalId()).substring(source.offset(),source.offset()+source.length())),"body source offset and length identify the exact uncompressed UTF-16 source substring");
        check(source.status()==200&&source.account().equals("선택 계정")&&source.profileRevision().equals("r2-auth-revision")&&source.responseSha256().length()==64,"each value retains response identity, account, revision and hash");
      }
      check(!document.toString().contains(apiKey)&&!document.values().toString().contains(apiKey),"raw-value DTO diagnostics remain masked outside explicit report serialization");
      Path output=directory.resolve("actual-values.zip");var saved=ScopeInspectionReport.write(output,snapshot);var files=zip(output);
      check(saved.values()==document.values().size()&&files.keySet().equals(Set.of("report.json","report.html","values.csv","manifest.json")),"explicit local ZIP contains all requested report representations");
      Object parsed=MinerJson.parse(files.get("report.json"),32*1024*1024,300000);
      check(parsed instanceof Map<?,?> root&&root.get("values") instanceof List<?> rows&&rows.stream().anyMatch(row->row instanceof Map<?,?> map&&apiKey.equals(map.get("value"))),"machine JSON contains the exact unmasked actual value");
      check(files.get("values.csv").contains("'=HYPERLINK")&&files.get("report.json").contains("=HYPERLINK"),"CSV formula protection does not change the authoritative machine JSON value");
      check(files.get("report.html").contains("&lt;svg onload=alert(1)&gt;")&&!files.get("report.html").contains("<svg onload=alert(1)>")&&files.get("report.html").contains("default-src 'none'"),"report HTML renders response markup as text and includes no injected active element");
      for(String representation:files.values())check(!representation.contains("REQUEST_AUTH_ONLY")&&!representation.contains("REQUEST_COOKIE_ONLY")&&!representation.contains("REQUEST_URL_ONLY"),"response-only export does not dump request profile or query credentials");
      byte[] prior=Files.readAllBytes(output);ioFailure(()->ScopeInspectionReport.write(output,snapshot),"existing report is refused without overwrite");check(Arrays.equals(prior,Files.readAllBytes(output)),"existing report bytes remain unchanged");
      Path cancelled=directory.resolve("cancelled.zip");Thread.currentThread().interrupt();try{ioFailure(()->ScopeInspectionReport.write(cancelled,snapshot),"interrupted export is cancelled before publication");}finally{Thread.interrupted();}
      check(!Files.exists(cancelled),"cancelled export leaves no final report");
      var missing=snapshot(List.of(new ScopeInspection.Page("https://report.test/past","GET","선택 계정",200,"JSON",true,"", "",null)),List.of());
      check(!ScopeInspectionReport.collect(missing).complete()&&ScopeInspectionReport.collect(missing).pages().get(0).note().contains("원본 없음"),"historical metadata without originals is explicitly unextractable");
      var huge=packet(store,"https://report.test/large","application/json","{\"password\":\""+"X".repeat(ScopeInspectionReport.MAX_VALUE+128)+"\"}",List.of());
      var limited=ScopeInspectionReport.collect(snapshot(List.of(page(huge)),List.of()));
      check(!limited.complete()&&!limited.pages().get(0).complete()&&limited.values().stream().noneMatch(v->v.name().equals("password")),"over-limit value is explicitly excluded rather than silently clipped into a false exact value");
      String expressionKey="ghp_"+"aB3dE6gH9jK2mN5pQ8sT1vW4yZ7cF0iL3oR6";
      var brokenJs=packet(store,"https://report.test/unsupported.js","application/javascript","const broken=; const apiKey='"+expressionKey+"' + suffix; const secretJoin='NOT_ACTUAL'.concat(other);",List.of());
      var brokenJsReport=ScopeInspectionReport.collect(snapshot(List.of(page(brokenJs)),List.of()));
      check(!brokenJsReport.complete()&&brokenJsReport.values().stream().noneMatch(v->v.value().equals(expressionKey)||v.value().equals("NOT_ACTUAL")),"failed JS parsing never exports expression-prefix literals or provider-pattern prefixes as actual values");
      var brokenJson=packet(store,"https://report.test/malformed.json","application/json","{\"apiKey\":\"JSON_PREFIX_NOT_ACTUAL\" + suffix,\"accessToken\":\"INDEPENDENT_LITERAL\"}",List.of());
      var brokenJsonReport=ScopeInspectionReport.collect(snapshot(List.of(page(brokenJson)),List.of()));
      check(!brokenJsonReport.complete()&&brokenJsonReport.values().stream().noneMatch(v->v.value().equals("JSON_PREFIX_NOT_ACTUAL")),"failed whole JSON validation rolls back staged values before conservative literal fallback");
      var unsupportedEntity=packet(store,"https://report.test/entities","text/html","<input name='fullName' value='Alice&nbsp;Bob &amp; Carol'>",List.of());
      var entityReport=ScopeInspectionReport.collect(snapshot(List.of(page(unsupportedEntity)),List.of()));
      check(!entityReport.complete()&&entityReport.pages().get(0).note().contains("미지원")&&entityReport.values().stream().anyMatch(v->v.value().equals("Alice&nbsp;Bob &amp; Carol")&&v.valueType().equals("RAW_LITERAL_UNDECODED")&&v.confidence().contains("파싱값 미확정")),"unsupported HTML entity preserves only the explicitly labeled raw literal and marks parsing partial");
      Path entityOutput=directory.resolve("raw-entity.zip");ScopeInspectionReport.write(entityOutput,snapshot(List.of(page(unsupportedEntity)),List.of()));var entityFiles=zip(entityOutput);
      check(entityFiles.get("report.html").contains("RAW_LITERAL_UNDECODED")&&entityFiles.get("report.json").contains("RAW_LITERAL_UNDECODED")&&entityFiles.get("values.csv").contains("RAW_LITERAL_UNDECODED"),"unsupported-entity raw-only state is visible in HTML JSON and CSV alike");
      var names=packet(store,"https://report.test/field-names","application/json","{\"className\":\"flex-row gap-2\",\"cssName\":\"layout-main\",\"ssn\":\"001-02-0003\",\"socialSecurityNumber\":\"101-22-0004\",\"customer_ssn\":\"202-33-0005\",\"customerSSN\":\"303-44-0006\"}",List.of());
      var nameValues=ScopeInspectionReport.collect(snapshot(List.of(page(names)),List.of())).values();
      check(nameValues.stream().noneMatch(value->Set.of("className","cssName").contains(value.name())),"className and cssName never become sensitive merely because normalized names contain ssn");
      check(nameValues.stream().filter(value->Set.of("ssn","socialSecurityNumber","customer_ssn","customerSSN").contains(value.name())&&value.category().equals("SENSITIVE")).count()==4,"exact acronym and tokenized SSN field names retain sensitive-value classification");
      var map=packet(store,"https://report.test/bundle.js.map","application/json","{\"sourcesContent\":[\"const api_key='RECOVERED_VALUE';\"]}",List.of());
      check(ScopeInspectionReport.collect(snapshot(List.of(page(map)),List.of())).pages().get(0).note().contains("소스맵"),"unmapped recovered source code has an explicit coordinate and parsing limitation");
      Path raced=directory.resolve("raced.zip");AtomicInteger won=new AtomicInteger(),lost=new AtomicInteger();ExecutorService writers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
      try{List<Future<?>>tasks=new ArrayList<>();for(int i=0;i<2;i++)tasks.add(writers.submit(()->{try{start.await();ScopeInspectionReport.write(raced,snapshot);won.incrementAndGet();}catch(FileAlreadyExistsException expected){lost.incrementAndGet();}catch(Exception error){throw new RuntimeException(error);}}));start.countDown();for(Future<?>task:tasks)task.get(30,TimeUnit.SECONDS);}finally{writers.shutdownNow();}
      check(won.get()==1&&lost.get()==1&&zip(raced).containsKey("report.json"),"competing writers publish one complete ZIP and never replace it");
      try(var paths=Files.list(directory)){check(paths.noneMatch(p->p.getFileName().toString().endsWith(".tmp")),"temporary report files are removed after success, conflict and cancellation");}
    }finally{try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    System.out.println("ScopeInspectionReportTest: "+checks+" checks passed (response-only exact values, no network)");
  }
}
