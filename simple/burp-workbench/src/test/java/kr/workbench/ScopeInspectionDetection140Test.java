package kr.workbench;

import java.nio.file.*;
import java.util.*;

/** Shared extraction, location evidence, useful PII recall and negative controls. No target requests. */
public final class ScopeInspectionDetection140Test extends WorkbenchTest {
  public static void main(String[] args)throws Exception {
    String body="{\"fullName\":\"Alice Johnson\",\"phone\":\"+1-555-0123\",\"ssn\":\"123-45-6789\",\"dob\":\"1990-03-15\","
        +"\"address\":{\"street\":\"Al-Buhtori St. 58\",\"city\":\"Amman\",\"state\":\"Amman Governorate\",\"zipCode\":\"11118\",\"country\":\"Jordan\"},"
        +"\"account_number\":\"8005691985\",\"framework\":\"Spring MVC\",\"was\":\"Apache Tomcat\",\"database\":\"H2 Oracle mode (test)\"}";
    String url="https://inspection140.test/api/me",headers="Content-Type: application/json\r\nServer: Werkzeug/2.0.1 Python/3.12.14";
    var parsed=ScopeInspectionReport.extractObserved(url,200,headers,body,"선택 계정","common-fixture");
    var live=ScopeInspectionAnalysis.analyze(url,200,headers,body,"선택 계정","common-fixture");
    for(String key:List.of("fullName","phone","ssn","dob","street","city","state","zipCode","country","account_number")) {
      var scalar=parsed.values().stream().filter(v->v.name().equals(key)).findFirst().orElseThrow();
      check(scalar.category().equals("SENSITIVE")&&scalar.classification().equals("PERSONAL_DATA"),"structured personal field is classified by shared rules: "+key);
      check(live.findings().stream().anyMatch(f->f.category().equals("SENSITIVE")&&f.fingerprint().equals(ScopeInspectionAnalysis.hash(scalar.value()))&&f.offset()==scalar.source().offset()),"runtime retains actual personal value fingerprint and exact original offset: "+key);
      check(!live.findings().toString().contains(scalar.value()),"ordinary live snapshot masks the personal value: "+key);
    }
    for(String field:List.of("framework","was","database"))check(live.findings().stream().anyMatch(f->f.title().endsWith(" · "+field)&&f.detail().contains("공통 값 파서")),"structured stack field is a product observation: "+field);
    for(var value:parsed.values())if(value.source().area().equals("DECODED_RESPONSE_BODY"))check(value.rawText().equals(body.substring(value.source().offset(),value.source().offset()+value.source().length())),"common parser preserves original JSON span");

    var controls=ScopeInspectionAnalysis.analyze("https://inspection140.test/app.js",200,"Content-Type: application/javascript",
        "const DOMTokenList=1; const client_secret='),fe?Re.createElement('; const credentials='same-origin'; const passwordStrength=0; const password=''; const jwt=null; const country='US'; const state='loading'; const city='Dropdown'; const fakeToken='LAB_ONLY_DEMO_TOKEN_NOT_A_REAL_SECRET'; const merchantApiKey='<MERCHANT_API_KEY>';",
        "anonymous","negative-controls");
    check(controls.findings().stream().noneMatch(f->f.category().equals("SECRET")&&Set.of("유효성 미검증","관측 값·용도 미확인").contains(f.state())),"code fragments, empty/null fields, DOMTokenList and placeholders are never promoted to real secret candidates");
    check(controls.findings().stream().noneMatch(f->f.category().equals("SENSITIVE")),"standalone state machine and country selector values do not become personal address data");
    check(controls.findings().stream().anyMatch(f->f.state().equals("예제값 가능")&&f.detail().contains("EXAMPLE_OR_PLACEHOLDER")),"explicit demo and merchant placeholder remain visible as examples");
    var css=ScopeInspectionAnalysis.analyze("https://inspection140.test/style.css",200,"Content-Type: text/css",".icon-mongodb:before{content:'\\f167'} .icon-phone:before{content:'\\f123'}","anonymous","css");
    check(css.findings().stream().noneMatch(f->Set.of("DATABASE","SECRET","SENSITIVE").contains(f.category())),"CSS icon labels are not application DB or personal data");
    var cookie=ScopeInspectionReport.extractObserved(url,200,"Content-Type: text/plain\r\nSet-Cookie: BenchmarkTest00077=ECHOOO; HttpOnly; Secure","ok","anonymous","cookie");
    check(cookie.values().stream().anyMatch(v->v.value().equals("ECHOOO")&&v.classification().equals("COOKIE_VALUE")),"test response cookie is classified as cookie value with unverified authentication purpose");
    var scheme=ScopeInspectionReport.extractObserved(url,200,"Content-Type: application/json","{\"type\":\"apiKey\",\"name\":\"X-Merchant-Api-Key\",\"bearerFormat\":\"JWT\",\"credentials\":\"same-origin\"}","anonymous","scheme");
    check(scheme.values().stream().noneMatch(v->Set.of("KEY","TOKEN","SENSITIVE").contains(v.category())),"OpenAPI scheme and header names are not extracted actual credentials");
    String loopback="http://127.0.0.1:18080/",jsonHeader="Content-Type: application/json";
    var urlOnly=ScopeInspectionAnalysis.analyze(loopback,200,jsonHeader,"{\"url\":\"http://127.0.0.1:18080/api/items\"}","Alice","short-baseline");
    var shortValues=ScopeInspectionAnalysis.analyze(loopback,200,jsonHeader,"{\"url\":\"http://127.0.0.1:18080/api/items\",\"userId\":1,\"balance\":0}","Alice","short-values");
    check(shortValues.candidates().stream().filter(ScopeInspection.Candidate::executable).count()==urlOnly.candidates().stream().filter(ScopeInspection.Candidate::executable).count(),"short account scalars do not disable or redact approved loopback navigation");
    check(shortValues.findings().stream().filter(f->f.title().endsWith(" · userId")||f.title().endsWith(" · balance")).allMatch(f->f.detail().startsWith("[가림")),"short account values stay masked in their own metadata rows");
    check(shortValues.candidates().stream().noneMatch(c->c.label().contains("공통 값 파서 · 동적 미해결")&&c.url().equals("http://127.0.0.1:18080/api/items")),"absolute static JSON URL is not mislabeled an unresolved dynamic API");
    String withUserinfo="https://admin:WireSecret!@scope.test/api/data?email=EncodedPrivate%40mail.test&code=PlainPrivateCode";
    String relative="/api/users?email=RelativePrivate%40mail.test&code=RelativePrivateCode";
    String hashRoute="#/profile?email=HashPrivate%40mail.test";
    String urlBody=MetadataExport.json(Map.of("apiUrl",withUserinfo,"endpoint",relative,"route",hashRoute));
    var privateUrls=ScopeInspectionAnalysis.analyze("https://scope.test/config",200,jsonHeader,urlBody,"Alice","private-url-metadata");
    String metadata=privateUrls.findings().toString()+privateUrls.note()+privateUrls.candidates().stream().map(c->c.label()+c.source()).toList();
    for(String privatePart:List.of("admin:WireSecret!","WireSecret!","EncodedPrivate%40mail.test","PlainPrivateCode","RelativePrivate%40mail.test","RelativePrivateCode","HashPrivate%40mail.test"))check(!metadata.contains(privatePart),"URL credentials and query PII never enter ordinary finding/history metadata");
    check(privateUrls.findings().stream().anyMatch(f->f.detail().contains("/api/users?[쿼리값가림]")),"relative API path stays visible while all query values are removed");
    check(privateUrls.candidates().stream().noneMatch(c->c.url().contains("admin:")||c.url().contains("WireSecret!")),"userinfo URLs are never retained as executable or inert transport candidates");
    String dynamicPrivate="fetch(`${host}/api/${id}?email=DynamicPrivate%40mail.test&code=DynamicPrivateCode`);";
    var dynamicMetadata=ScopeInspectionAnalysis.analyze("https://scope.test/app.js",200,"Content-Type: application/javascript",dynamicPrivate,"Alice","private-dynamic-metadata");
    check(dynamicMetadata.findings().stream().anyMatch(f->f.detail().contains("${host}/api/${id}?[쿼리값가림]")),"dynamic path placeholders remain visible without disclosing query values");
    check(!dynamicMetadata.findings().toString().contains("DynamicPrivate"),"dynamic API query credentials cannot enter ordinary metadata");
    check(!ScopeInspectionAnalysis.display("endpoint="+withUserinfo,1000).contains("WireSecret!")&&!ScopeInspectionAnalysis.display("endpoint="+withUserinfo,1000).contains("PlainPrivateCode"),"generic diagnostic URL display removes userinfo and query values too");
    var exactUrls=ScopeInspectionReport.extractObserved("https://scope.test/config",200,jsonHeader,urlBody,"Alice","private-url-metadata");
    check(exactUrls.values().stream().anyMatch(v->v.value().equals(withUserinfo))&&exactUrls.values().stream().anyMatch(v->v.value().equals(relative)),"explicit transient raw-report extraction preserves exact URI credentials and query values separately");
    for(var hostCase:Map.of("http://10.10.10.10","{\"internalHost\":\"10.10.10.10\"}","http://app.internal","{\"DB_HOST\":\"app.internal\",\"DB_USER\":\"app_user\"}").entrySet()) {
      String page="<a href='/reports'>Reports</a><script type='application/json'>"+hostCase.getValue()+"</script>";
      var internalHost=ScopeInspectionAnalysis.analyze(hostCase.getKey()+"/",200,"Content-Type: text/html",page,"Alice","host-observation");
      check(internalHost.candidates().stream().anyMatch(c->c.url().equals(hostCase.getKey()+"/reports")&&c.method().equals("GET")&&c.executable()),"observed internal or DB host does not block legitimate navigation on that same scoped host");
      check(internalHost.findings().stream().filter(f->f.title().endsWith(" · internalHost")||f.title().endsWith(" · DB_HOST")||f.title().endsWith(" · DB_USER")).allMatch(f->f.detail().startsWith("[가림")),"noncredential host and DB user values remain masked in their own information rows");
    }
    String uriEmails=MetadataExport.json(Map.of("databaseUrl","postgresql://app:DB_DEMO_PASSWORD@db.internal/app",
        "apiUrl","https://app:HTTP_URI_PASSWORD@api.internal/api/data","relativeUrl","//app:RELATIVE_URI_PASSWORD@db.internal/app",
        "contactEmail","real@inspection140.test","supportUrl","https://service.test/help?contact=notify@inspection140.test",
        "routeUrl","https://service.test/users/member@inspection140.test","mailLink","mailto:public@inspection140.test"));
    var uriParsed=ScopeInspectionReport.extractObserved("https://inspection140.test/config",200,jsonHeader,uriEmails,"Alice","uri-email-controls");
    var uriLive=ScopeInspectionAnalysis.analyze("https://inspection140.test/config",200,jsonHeader,uriEmails,"Alice","uri-email-controls");
    for(String credentialEmail:List.of("DB_DEMO_PASSWORD@db.internal","HTTP_URI_PASSWORD@api.internal","RELATIVE_URI_PASSWORD@db.internal")) {
      check(uriParsed.values().stream().noneMatch(v->v.category().equals("SENSITIVE")&&v.name().equals("Email")&&v.value().equals(credentialEmail)),"URI authority credentials are not duplicated as report email information");
      String fingerprint=ScopeInspectionAnalysis.hash(credentialEmail);
      check(uriLive.findings().stream().noneMatch(f->(f.title().contains("이메일")||f.title().endsWith(" · Email"))&&(f.fingerprint().equals(fingerprint)||f.fingerprint().equals(fingerprint.substring(0,16)))),"URI authority credentials are not duplicated as live email findings");
    }
    for(String actualEmail:List.of("real@inspection140.test","notify@inspection140.test","member@inspection140.test","public@inspection140.test")) {
      check(uriParsed.values().stream().anyMatch(v->v.category().equals("SENSITIVE")&&v.value().equals(actualEmail)),"real field, query, path and mailto email addresses remain report observations");
      String fingerprint=ScopeInspectionAnalysis.hash(actualEmail);
      check(uriLive.findings().stream().anyMatch(f->f.fingerprint().equals(fingerprint)||f.fingerprint().equals(fingerprint.substring(0,16))),"real email address still has live location or fingerprint evidence");
    }
    String escapedConnection="{\"databaseUrl\":\"postgresql:\\/\\/app:ESCAPED_URI_PASSWORD@db.internal\\/app\",\"contactEmail\":\"real@inspection140.test\"}";
    check(ScopeInspectionReport.extractObserved("https://inspection140.test/config",200,jsonHeader,escapedConnection,"Alice","escaped-uri").values().stream().noneMatch(v->v.name().equals("Email")&&v.value().equals("ESCAPED_URI_PASSWORD@db.internal")),"escaped JSON URI separators do not create false email candidates");
    var escapedLive=ScopeInspectionAnalysis.analyze("https://inspection140.test/config",200,jsonHeader,escapedConnection,"Alice","escaped-uri");
    check(escapedLive.findings().stream().noneMatch(f->f.title().contains("이메일")&&f.fingerprint().equals(ScopeInspectionAnalysis.hash("ESCAPED_URI_PASSWORD@db.internal").substring(0,16))),"escaped JSON URI credentials are excluded by live email analysis too");
    StringBuilder uriNoise=new StringBuilder();for(int i=0;i<40;i++)uriNoise.append("postgresql://app:URI_PASSWORD_").append(i).append("@db.internal/app\n");uriNoise.append("contact=real@inspection140.test\n");
    var afterUriNoise=ScopeInspectionAnalysis.analyze("https://inspection140.test/config",200,"Content-Type: text/plain",uriNoise.toString(),"Alice","uri-noise-budget");
    check(afterUriNoise.findings().stream().anyMatch(f->f.title().equals("이메일 형식 정보")&&f.fingerprint().equals(ScopeInspectionAnalysis.hash("real@inspection140.test").substring(0,16))),"excluded URI credentials do not consume the bounded real-email result budget");
    String oracleConnection="jdbc:oracle:thin:app/ORACLE_DB_PASSWORD@db.internal:1521:LAB";
    var oracleUri=ScopeInspectionReport.extractObserved("https://inspection140.test/config",200,"Content-Type: text/plain",oracleConnection,"Alice","oracle-email-control");
    check(oracleUri.values().stream().anyMatch(v->v.category().equals("DATABASE")&&v.value().equals(oracleConnection))&&oracleUri.values().stream().noneMatch(v->v.name().equals("Email")),"nonhierarchical JDBC credentials remain one DB connection observation instead of a false email");

    String js="const token='REAL_SECRET_7f3b9d82c1264ea0'; fetch(`${host}/api/items/${id}`); const endpoint=`/api/static\\x2fitems`;";
    var templates=ScopeInspectionReport.extractObserved("https://inspection140.test/app.js",200,"Content-Type: application/javascript",js,"anonymous","template");
    check(templates.values().stream().anyMatch(v->v.value().equals("${host}/api/items/${id}")&&v.classification().equals("DYNAMIC_UNRESOLVED")&&!v.attributes().containsKey("resolvedEndpoint")),"dynamic API is preserved without evaluation or invented request endpoint");
    check(templates.values().stream().anyMatch(v->v.value().equals("/api/static/items")&&v.rawText().equals("`/api/static\\x2fitems`")),"static API value and escaped source stay separate");
    var large=ScopeInspectionReport.extractObserved("https://inspection140.test/bundle.js",200,"Content-Type: application/javascript","/*"+"padding".repeat(40000)+"*/"+js,"anonymous","large-lexical");
    check(large.values().stream().anyMatch(v->v.value().equals("REAL_SECRET_7f3b9d82c1264ea0")),"large bundle lexical pass retains a named literal beyond 256KiB without another whole-bundle AST");
    check(large.values().stream().anyMatch(v->v.value().equals("${host}/api/items/${id}")&&v.classification().equals("DYNAMIC_UNRESOLVED")),"large bundle lexical pass preserves unresolved API templates");
    check(!large.toString().contains("REAL_SECRET_"),"transient parser diagnostics never print raw values");
    String compatibility="const broken=; const cls=`lucide-${name.replace(/([a-z])([A-Z])/g,\"$1-$2\")}`; fetch(`${w}/api/transfer`,{method:'POST'}); const host=this.hostServer+`/api/Users`; this.http.get(this.hostServer+`/rest/user/authentication-details/`,{params:e});";
    var compatible=ScopeInspectionReport.extractObserved("https://inspection140.test/unsupported.js",200,"Content-Type: application/javascript",compatibility,"Alice","compatibility");
    check(compatible.values().stream().anyMatch(v->v.value().equals("${w}/api/transfer")&&v.classification().equals("DYNAMIC_UNRESOLVED")),"independently parsed non-API library template boundary does not hide a later observed dynamic API");
    for(String literal:List.of("/api/Users","/rest/user/authentication-details/"))check(compatible.values().stream().anyMatch(v->v.value().equals(literal)&&v.valueType().equals("STRING")&&v.rawText().equals("`"+literal+"`")&&v.classification().equals("STATIC_FRAGMENT_UNRESOLVED")&&v.attributes().getOrDefault("resolution","").equals("STATIC_FRAGMENT_UNRESOLVED")&&!v.attributes().containsKey("resolvedEndpoint")),"concatenated API literal is retained as an unresolved fragment without guessing its host: "+literal);
    var sourceMap=ScopeInspectionAnalysis.analyze("https://inspection140.test/assets/app.js",200,"Content-Type: application/javascript","const harmless=1;\n//# sourceMappingURL=maps/app.js.map","anonymous","map");
    check(sourceMap.candidates().stream().anyMatch(c->c.source().equals("소스맵 참조")&&c.url().equals("https://inspection140.test/assets/maps/app.js.map")&&c.executable()),"only an explicitly observed source map reference becomes a scoped GET candidate");
    check(sourceMap.candidates().stream().noneMatch(c->c.source().equals("소스맵 참조")&&c.url().equals("https://inspection140.test/assets/app.js.map")),"source-map file names are not guessed");

    Path dir=Files.createTempDirectory("inspection140-common-");
    try(var store=new DiskTrafficStore()) {
      var history=new ScopeInspectionHistory(dir.resolve("history"),"11111111-1111-4111-8111-111111111111",()->"include=https://scope.test/");
      var historyOptions=new ScopeInspection.Options("https://scope.test/config",10,1,0,2000,1024*1024,1,false,false,List.of(new ScopeInspection.Profile("alice","Alice",Map.of(),null)));
      List<ScopeInspection.Finding> metadataFindings=new ArrayList<>(privateUrls.findings());metadataFindings.addAll(dynamicMetadata.findings());
      history.save(historyOptions,new ScopeInspection.Snapshot("url-redaction",ScopeInspection.State.COMPLETED,"",1,1,0,0,0,0,metadataFindings,List.of(),List.of(),10,20));
      String storedHistory=Files.readString(dir.resolve("history/runs-v1.json"));
      check(!storedHistory.contains("WireSecret!")&&!storedHistory.contains("PlainPrivateCode")&&!storedHistory.contains("RelativePrivate")&&!storedHistory.contains("DynamicPrivate"),"saved masked history excludes URI credentials and absolute, relative and dynamic query values");
      var packet=ScopeInspectionReportTest.packet(store,url,"application/json",body,List.of());
      var actualLive=ScopeInspectionAnalysis.analyze(url,200,"Content-Type: application/json",body,"선택 계정",packet.id());
      var document=ScopeInspectionReport.collect(ScopeInspectionReportTest.snapshot(List.of(ScopeInspectionReportTest.page(packet)),actualLive.findings()));
      for(String field:List.of("ssn","dob","street","city","state","zipCode","country","account_number"))check(document.values().stream().anyMatch(v->v.name().equals(field)&&v.provenance().equals("PRODUCT_FINDING")),"explicit report links structured fields to real masked runtime product evidence: "+field);
      Path zip=dir.resolve("report.zip");ScopeInspectionReport.write(zip,ScopeInspectionReportTest.snapshot(List.of(ScopeInspectionReportTest.page(packet)),actualLive.findings()));
      String json=ScopeInspectionReportTest.zip(zip).get("report.json");
      check(json.contains("\"coverage\"")&&json.contains("\"schemaVersion\":2"),"exact-value report includes per-path coverage contract without a separate request");
      String pendingBody="{\"apiKey\":\"PENDING_PARSE_ONLY_123\"}";
      var pendingPacket=ScopeInspectionReportTest.packet(store,"https://inspection140.test/pending","application/json",pendingBody,List.of());
      String pendingNote="원문 수신 · 중지 경계에서 제품 응답 분석 미완료";
      var pendingPage=new ScopeInspection.Page(pendingPacket.url(),"GET","선택 계정",200,"RESPONSE_PENDING_ANALYSIS",false,pendingNote,"",pendingPacket,"r-pending");
      var pendingSnapshot=new ScopeInspection.Snapshot("pending-original",ScopeInspection.State.STOPPED,"",1,0,0,0,0,1,List.of(),List.of(pendingPage),List.of(),10,20);
      var pendingDocument=ScopeInspectionReport.collect(pendingSnapshot);
      check(!pendingDocument.complete()&&!pendingDocument.pages().get(0).complete(),"local exact-value extraction cannot promote a received-but-unanalyzed product response to complete");
      check(pendingDocument.pages().get(0).note().contains(pendingNote)&&pendingDocument.pages().get(0).note().contains("RESPONSE_PENDING_ANALYSIS")&&pendingDocument.pages().get(0).note().contains("로컬 원문 값 추출:"),"export retains the original pending-product reason separately from local value parsing");
      var pendingValue=pendingDocument.values().stream().filter(v->v.value().equals("PENDING_PARSE_ONLY_123")).findFirst().orElseThrow();
      check(pendingValue.rawText().equals(pendingBody.substring(pendingValue.source().offset(),pendingValue.source().offset()+pendingValue.source().length()))&&pendingValue.source().originalId().equals(pendingPacket.id())&&pendingValue.source().responseSha256().length()==64&&pendingValue.provenance().equals("REPORT_SUPPLEMENT"),"pending original values retain exact source evidence without inventing a product finding");
      var completeDocument=ScopeInspectionReport.collect(ScopeInspectionReportTest.snapshot(List.of(ScopeInspectionReportTest.page(pendingPacket)),List.of()));
      check(completeDocument.pages().get(0).complete()&&completeDocument.pages().get(0).note().equals("응답 값 추출 범위 내 완료"),"already-complete product pages retain their existing export note and completion behavior");
    } finally {try(var paths=Files.walk(dir)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    System.out.println("ScopeInspectionDetection140Test: "+checks+" checks passed (shared parser, observed response fixtures; no network)");
  }
}
