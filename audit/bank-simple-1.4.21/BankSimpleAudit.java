package kr.workbench;

import burp.*;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.*;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.*;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** Executes the immutable released product against the real local VulnBank application.
 * Montoya-compatible host/HTTP adapter; never a native Burp or simulated-response claim. */
public final class BankSimpleAudit extends WorkbenchTest {
  static final String EXPECTED_JAR="9d3d20af946add3498da79a021be31fafe8d84e046352463c4a559616187301c";
  String base; Path out,published; List<Map<String,Object>> actors;
  final Set<String> secrets=new LinkedHashSet<>();
  final List<Map<String,Object>> checks=new ArrayList<>(),transport=new CopyOnWriteArrayList<>(),comparisons=new ArrayList<>();
  final AtomicInteger sequence=new AtomicInteger();
  final burp.BurpExtender entry=new burp.BurpExtender();
  MontoyaApi api;WorkbenchExtension wb;AuthComparePanel auth;ResponseReconPanel recon;JsAnalysisPanel js;
  ActivityPanel activity;BountySupportPanel bounty;Object payload;
  final java.net.http.HttpClient client=java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
      .followRedirects(java.net.http.HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

  static Object field(Object o,String name){for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException ignored){}catch(Exception e){throw new IllegalStateException(e);}throw new IllegalArgumentException(name);}
  static Object invoke(Object o,String name,Class<?>[] types,Object... args){try{var m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}catch(Exception e){throw new IllegalStateException(e);}}
  static Map<String,Object> obj(Object... pairs){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)r.put(pairs[i].toString(),pairs[i+1]);return r;}
  static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
  static void require(boolean b,String message){if(!b)throw new AssertionError(message);}
  static void await(BooleanSupplier done,int seconds)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<until){edt(()->{});if(done.getAsBoolean()){Thread.sleep(120);edt(()->{});if(done.getAsBoolean())return;}Thread.sleep(30);}throw new AssertionError("Timed out after "+seconds+" seconds");}
  interface Action{void run()throws Exception;}
  void test(String id,String feature,String title,Action action){long start=System.nanoTime();Map<String,Object> row=obj("id",id,"feature",feature,"title",title);try{action.run();row.put("status","PASS");}catch(Throwable e){while(e.getCause()!=null)e=e.getCause();row.put("status","FAIL");row.put("reason",e.getClass().getSimpleName()+": "+Objects.toString(e.getMessage(),""));e.printStackTrace();}row.put("millis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));checks.add(row);System.out.println("BANK_FEATURE "+id+" "+row.get("status"));}
  void save(String name,Object value)throws Exception{String text=MetadataExport.json(value)+"\n";Files.writeString(out.resolve(name+".json"),text);for(String secret:secrets)if(secret!=null&&!secret.isEmpty())text=text.replace(secret,"[LAB_CREDENTIAL_REDACTED]");Files.writeString(published.resolve(name+".json"),text);}

  static HttpRequest enhanced(HttpRequest original){
    return proxy(HttpRequest.class,(p,m,a)->{
      URI u=URI.create(original.url());String path=u.getRawPath().isEmpty()?"/":u.getRawPath();
      switch(m.getName()){
        case "pathWithoutQuery":return path;case "path":return path+(u.getRawQuery()==null?"":"?"+u.getRawQuery());
        case "httpVersion":return "HTTP/1.1";
        case "containsHeader":return original.headers().stream().anyMatch(h->h.name().equalsIgnoreCase(a[0].toString()));
        case "withBody","withMethod","withHeader","withRemovedHeader":return enhanced((HttpRequest)m.invoke(original,a));
        case "withPath":return wire(u.resolve(a[0].toString()).toString(),original.method(),original.headers(),original.body().getBytes());
        default:return m.invoke(original,a);
      }
    });
  }
  static HttpRequest wire(String url,String method,List<HttpHeader> headers,byte[] body){return enhanced(ScopeInspectionFixture.wireRequest(url,method,headers,body));}
  void factory(){
    ScopeInspectionFixture.installFactory();MontoyaObjectFactory previous=ObjectFactoryLocator.FACTORY;
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->{
      if(m.getName().equals("httpHeader"))return header(a[0].toString(),a[1].toString());
      Object result=m.invoke(previous,a);return result instanceof HttpRequest q?enhanced(q):result;
    });
  }
  HttpRequest request(String path,int actor){List<HttpHeader> h=new ArrayList<>();h.add(header("Accept","application/json"));if(actor>=0&&actor<actors.size()){String token=actors.get(actor).get("token").toString();h.add(header("Authorization","Bearer "+token));h.add(header("Cookie","token="+token));}return wire(base+path,"GET",h,new byte[0]);}
  HttpRequest post(String path,int actor,Object body){return request(path,actor).withMethod("POST").withHeader("Content-Type","application/json").withBody(MetadataExport.json(body));}
  HttpRequestResponse send(HttpRequest q){try{return exchange(q);}catch(Exception e){throw new IllegalStateException(e);}}
  HttpRequestResponse exchange(HttpRequest request)throws Exception{
    require(EnvironmentCatalog.origin(URI.create(request.url())).equals(base),"Loopback origin boundary: "+request.url());
    int id=sequence.incrementAndGet();String name=String.format(Locale.ROOT,"%04d",id);Path folder=out.resolve("transport");Files.createDirectories(folder);
    byte[] rawRequest=request.toByteArray().getBytes();Files.write(folder.resolve(name+".request.http"),rawRequest);
    var builder=java.net.http.HttpRequest.newBuilder(URI.create(request.url())).timeout(Duration.ofSeconds(15))
      .method(request.method(),request.body().length()==0?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofByteArray(request.body().getBytes()));
    for(HttpHeader h:request.headers())if(!Set.of("host","connection","content-length","upgrade","http2-settings").contains(h.name().toLowerCase(Locale.ROOT)))builder.header(h.name(),h.value());
    var received=client.send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());List<HttpHeader> headers=new ArrayList<>();received.headers().map().forEach((k,v)->v.forEach(x->headers.add(header(k,x))));
    HttpResponse response=ScopeInspectionFixture.wireResponse(received.statusCode(),headers,received.body());registerTrafficPacket(request,response);
    Files.write(folder.resolve(name+".response.http"),response.toByteArray().getBytes());
    Files.write(folder.resolve(name+".body.bin"),received.body());
    transport.add(obj("index",id,"url",request.url(),"method",request.method(),"status",received.statusCode(),"requestSHA256",sha(rawRequest),"responseSHA256",sha(response.toByteArray().getBytes()),"bodySHA256",sha(received.body()),"bodyBytes",received.body().length,"privateRequestFile","transport/"+name+".request.http","privateResponseFile","transport/"+name+".response.http"));
    if(wb!=null){
      var tool=proxy(burp.api.montoya.core.ToolSource.class,(p,m,a)->m.getName().equals("toolType")?ToolType.EXTENSIONS:m.getName().equals("isFromTool")?true:def(m));
      HttpRequestToBeSent outgoing=proxy(HttpRequestToBeSent.class,(p,m,a)->switch(m.getName()){case "messageId"->id;case "toolSource"->tool;case "annotations"->null;default->HttpRequest.class.getMethod(m.getName(),m.getParameterTypes()).invoke(request,a);});
      HttpResponseReceived incoming=proxy(HttpResponseReceived.class,(p,m,a)->switch(m.getName()){case "messageId"->id;case "toolSource"->tool;case "annotations"->null;case "initiatingRequest"->request;default->HttpResponse.class.getMethod(m.getName(),m.getParameterTypes()).invoke(response,a);});
      wb.handleHttpRequestToBeSent(outgoing);wb.handleHttpResponseReceived(incoming);
    }
    return proxy(HttpRequestResponse.class,(p,m,a)->switch(m.getName()){case "request"->request;case "response"->response;default->def(m);});
  }
  void initialize()throws Exception{
    factory();MontoyaApi fallback=SimpleWorkflowTest.previewApi();Http http=proxy(Http.class,(p,m,a)->m.getName().equals("sendRequest")?exchange((HttpRequest)a[0]):m.invoke(fallback.http(),a));
    api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http")?http:m.invoke(fallback,a));
    IExtensionHelpers helpers=proxy(IExtensionHelpers.class,(p,m,a)->{
      if(m.getName().equals("buildHttpService")){String host=a[0].toString();int port=((Number)a[1]).intValue();String scheme=a[2] instanceof Boolean b?(b?"https":"http"):a[2].toString();return proxy(IHttpService.class,(pp,mm,aa)->switch(mm.getName()){case "getHost"->host;case "getPort"->port;case "getProtocol"->scheme;default->def(mm);});}
      if(m.getName().equals("analyzeRequest")){IHttpService s=(IHttpService)a[0];HttpRequest q=ScopeInspectionFixture.parseRequest(ScopeInspectionFixture.service(s.getHost(),s.getPort(),s.getProtocol().equalsIgnoreCase("https")),(byte[])a[1]);return proxy(IRequestInfo.class,(pp,mm,aa)->switch(mm.getName()){case "getUrl"->URI.create(q.url()).toURL();case "getMethod"->q.method();case "getHeaders"->q.headers().stream().map(HttpHeader::toString).toList();case "getBodyOffset"->ScopeInspectionFixture.bodyOffset((byte[])a[1]);default->def(mm);});}
      if(m.getName().equals("bytesToString"))return new String((byte[])a[0],StandardCharsets.ISO_8859_1);
      if(m.getName().equals("stringToBytes"))return a[0].toString().getBytes(StandardCharsets.ISO_8859_1);
      return def(m);
    });
    IBurpExtenderCallbacks fallbackCallbacks=(IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class);
    IBurpExtenderCallbacks callbacks=proxy(IBurpExtenderCallbacks.class,(p,m,a)->m.getName().equals("getHelpers")?helpers:m.getName().equals("isInScope")?a[0].toString().startsWith(base+"/"):m.invoke(fallbackCallbacks,a));
    entry.registerExtenderCallbacks(callbacks);edt(()->entry.initialize(api));wb=(WorkbenchExtension)field(entry,"workbench");payload=field(entry,"payloadRunner");
    auth=(AuthComparePanel)field(wb,"authCompare");recon=(ResponseReconPanel)field(wb,"responseRecon");js=(JsAnalysisPanel)field(wb,"jsAnalysis");activity=(ActivityPanel)field(wb,"activity");bounty=(BountySupportPanel)field(wb,"bountySupport");
    wb.dialogOpener=(owner,title,body,modal,closed)->null;
    ((WorkbenchScope)field(wb,"localScope")).add(base+"/",false);
    ((AtomicReference<WorkbenchExtension.CaptureMode>)field(wb,"captureMode")).set(WorkbenchExtension.CaptureMode.SCOPE);
  }
  void settleObservers()throws Exception{await(()->((CaptureObservations)field(wb,"captureObservations")).pending()==0,30);recon.worker.submit(()->{}).get(30,TimeUnit.SECONDS);activity.worker.submit(()->{}).get(30,TimeUnit.SECONDS);await(js::idle,90);Thread.sleep(400);edt(()->{});}
  void compare(String id,HttpRequest q,int baseline,String[] policy,String marker)throws Exception{
    HttpResponse ref=send(q).response();require(ref.statusCode()==200,"Reference HTTP200");int old=auth.runs.size();
    edt(()->{auth.writes.setSelected(false);auth.matrixRules.setText(q.method()+" "+q.pathWithoutQuery()+" "+String.join(" ",policy));auth.enqueue(q,ref,auth.context());});
    await(()->auth.active.get()==0,90);require(auth.runs.size()==old+1,"Actual auth comparison row");
    var run=auth.runs.get(auth.runs.size()-1);List<Map<String,Object>> cells=new ArrayList<>();
    for(int i=0;i<4;i++){var packet=run.packets[i];String body=packet.response==null?"":packet.response.bodyToString();cells.add(obj("actor",i==3?"anonymous":actors.get(i).get("name"),"expected",policy[i],"referenceActor",i==baseline,"status",packet.response==null?0:(int)packet.response.statusCode(),"protectedMarkerObserved",body.contains(marker),"kind",packet.finding().kind().name(),"verdict",packet.finding().verdict().name(),"reason",packet.finding().note()));}
    comparisons.add(obj("id",id,"url",q.url(),"method",q.method(),"baseline",baseline,"cells",cells));
    edt(()->auth.evidence.showRun(run));
  }
  void run()throws Exception{
    initialize();
    test("TR-01","traffic","실제 은행 HTTP 원본과 SCOPE 수집",()->{
      for(String p:List.of("/","/healthz","/static/dashboard.js","/static/merchant.js","/static/openapi.json"))send(request(p,-1));
      await(()->((DiskTrafficStore)field(wb,"trafficStore")).size()>=5,15);require(!WorkbenchScope.contains(api,"http://127.0.0.1:24999/outside"),"Different port excluded");
    });
    test("AU-01","auth","실제 세 계정 연결과 보호/취약 조회 비교",()->{
      for(int i=0;i<3;i++){int slot=i;HttpRequest q=request("/dashboard",i);HttpResponse r=send(q).response();edt(()->{var obs=auth.prepareObservation(q,r);require(obs!=null,"Actual credential present");auth.applyObservation(obs);auth.assign(slot,auth.credentials.get(obs.key()));auth.profileNames[slot].setText(actors.get(slot).get("name").toString());});}edt(()->auth.anonymous(3));
      compare("AUTH-IDOR-BALANCE",request("/check_balance/"+actors.get(1).get("account"),1),1,new String[]{"ALLOW","ALLOW","DENY","DENY"},"wb_alice");
      compare("AUTH-PROTECTED-ADMIN",request("/sup3r_s3cr3t_admin",0),0,new String[]{"ALLOW","DENY","DENY","DENY"},"wb_alice");
      compare("AUTH-PUBLIC-CONTROL",request("/healthz",0),0,new String[]{"ALLOW","ALLOW","ALLOW","ALLOW"},"ok");save("auth-comparisons",comparisons);
    });
    test("JS-01","js","실제 JS·OpenAPI에서 API 후보 추출",()->{
      settleObservers();var cache=(JsSourceCache)field(js,"cache");List<Object> rows=new ArrayList<>();
      for(var e:cache.snapshot().entrySet())if(e.getValue().result()!=null){var r=e.getValue().result();rows.add(obj("url",e.getKey(),"status",r.status(),"endpoints",r.endpoints().stream().map(x->obj("url",x.url(),"method",x.method(),"complete",x.complete())).toList()));}
      require(!rows.isEmpty(),"Actual source analysis results");save("js-analysis",rows);
    });
    test("EN-01","environment","실제 서버 관찰과 정상·미존재·문서 경로",()->{
      EnvironmentPanel env=(EnvironmentPanel)field(wb,"environments");edt(()->{env.delay.setValue(25);env.budget.setValue(8);env.depth.setValue(0);env.follow.setSelected(false);env.runUrls(List.of(base+"/healthz",base+"/wb-bank-missing-1421",base+"/static/openapi.json",base+"/.env"));});await(()->env.job==null,60);
      save("environment",env.catalog.sites.values().stream().map(x->obj("origin",x.origin,"detections",x.detections.values().toString())).toList());
      save("path-results",env.packets.values().stream().map(p->obj("url",p.url(),"state",p.state(),"status",p.result()==null?0:p.result().status(),"kind",p.result()==null?"":p.result().finding().kind().toString())).toList());
      require(env.packets.size()>=4,"Path scanner actual response records");
    });
    test("RE-01","response","실제 SQL 오류 및 보안 헤더 후보",()->{
      var error=send(request("/check_balance/%27",-1));settleObservers();save("response-findings",recon.issues.values().stream().map(f->obj("rule",f.rule(),"url",f.url(),"location",f.location(),"evidence",f.evidence())).toList());
      save("sql-error",obj("status",(int)error.response().statusCode(),"body",error.response().bodyToString()));require(error.response().statusCode()==500,"Actual SQL syntax error response");require(!recon.issues.isEmpty(),"Response analyzer candidates present");
    });
    test("EX-01","exposure","실제 관측 제품과 외부 조회 미실행 상태",()->{
      ExposureResearchPanel panel=(ExposureResearchPanel)field(wb,"exposureResearch");edt(()->panel.target.setText(base));
      save("exposure-research",obj("observations",panel.observations.toString(),"query",panel.query.getText(),"cveResultRows",panel.cveModel.getRowCount(),"freshness",panel.freshness.getText(),"externalProviderLookup","NOT_RUN: no external CVE/Google provider calls; loopback lab only"));require(panel.cveModel.getRowCount()==0,"No fabricated external CVE results");
    });
    test("PL-01","payload","실제 입력값 검사 패널의 SQL 조건 대조",()->{
      edt(()->{((JTextField)field(payload,"host")).setText(URI.create(base).getHost());((JSpinner)field(payload,"port")).setValue(URI.create(base).getPort());((JComboBox<?>)field(payload,"profile")).setSelectedItem(kr.payloadrunner.Engine.Profile.SQLI);((JComboBox<?>)field(payload,"encoding")).setSelectedItem(kr.payloadrunner.Engine.Encoding.URL);((JComboBox<?>)field(payload,"mode")).setSelectedIndex(1);
        ((JTextArea)field(payload,"request")).setText("GET /check_balance/"+kr.payloadrunner.Engine.MARK+" HTTP/1.1\r\nHost: "+URI.create(base).getRawAuthority()+"\r\n\r\n");((JTextField)field(payload,"base")).setText(actors.get(1).get("account").toString());((JTextArea)field(payload,"payloads")).setText("{{BASE}}' AND '1'='1' -- \n{{BASE}}' AND '1'='2' -- ");((JSpinner)field(payload,"budget")).setValue(5);((JSpinner)field(payload,"interval")).setValue(25);invoke(payload,"startScan",new Class<?>[]{});});
      await(()->!(Boolean)field(payload,"running"),60);var model=(javax.swing.table.DefaultTableModel)field(payload,"model");List<Object> rows=new ArrayList<>();for(int i=0;i<model.getRowCount();i++){List<Object> row=new ArrayList<>();for(int j=0;j<model.getColumnCount();j++)row.add(model.getValueAt(i,j));rows.add(row);}save("payload-sql",obj("columns",java.util.stream.IntStream.range(0,model.getColumnCount()).mapToObj(model::getColumnName).toList(),"rows",rows,"status",((JLabel)field(payload,"status")).getText()));require(model.getRowCount()>0,"Real payload panel results");
    });
    test("BO-01","bounty","실제 잔액 응답의 변수 추출과 순차 재현",()->{
      String account=actors.get(1).get("account").toString();String raw="GET /check_balance/"+account+" HTTP/1.1\r\nHost: "+URI.create(base).getRawAuthority()+"\r\n\r\n\n===STEP===\nGET /check_balance/{{account|URL}} HTTP/1.1\r\nHost: "+URI.create(base).getRawAuthority()+"\r\n\r\n";
      var plan=BountyWorkflow.parse(base,raw,"1.account=$.account_number",false,10,2);List<BountyWorkflow.Result> rows=new ArrayList<>();BountyWorkflow.run(api,plan,new AtomicBoolean(),rows::add,BountyWorkflow.checks(plan,"1.HTTP=200\n2.$.username=wb_alice"));save("workflow",rows.stream().map(r->obj("step",r.step(),"status",r.response()==null?0:(int)r.response().statusCode(),"state",r.state())).toList());require(rows.size()==2&&rows.get(1).state().contains("기대 조건 일치"),"Actual chained result match");
      var rr=send(request("/check_balance/"+account,-1));edt(()->bounty.captureObserved(List.of(rr),"로컬 은행 Alice 잔액 접근"));
      Path archive=out.resolve("redacted-evidence.zip");bounty.writeEvidence(archive,"로컬 VulnBank","실습 계정","Alice 잔액 접근",List.copyOf(bounty.evidence),Set.copyOf(bounty.evidenceHeaders),Set.copyOf(bounty.evidenceSecrets),true,activity.tracker.save(),null,List.of());
      try(var zip=new java.util.zip.ZipFile(archive.toFile())){require(zip.size()>0,"Evidence archive populated");for(var e:Collections.list(zip.entries()))if(!e.isDirectory()){String text=new String(zip.getInputStream(e).readAllBytes(),StandardCharsets.UTF_8);for(String secret:secrets)require(secret.isEmpty()||!text.contains(secret),"Evidence credentials redacted");}}
      Files.copy(archive,published.resolve("redacted-evidence.zip"));save("evidence",obj("items",bounty.evidence.size(),"sha256",sha(Files.readAllBytes(archive)),"credentialsRedacted",true));
    });
    test("DT-01","data","실제 계정 잔액 JSON 구조 비교",()->{
      var a=send(request("/check_balance/"+actors.get(1).get("account"),-1));var b=send(request("/check_balance/"+actors.get(2).get("account"),-1));StructuredComparePanel panel=(StructuredComparePanel)field(wb,"structuredCompare");
      edt(()->{panel.loadSource(true,a);panel.loadSource(false,b);panel.compare();});await(()->!panel.isComparing()&&panel.result!=null,30);save("structured-comparison",obj("leftAccount","wb_alice","rightAccount","wb_bob","changes",panel.result.changes().toString(),"changeCount",panel.result.changes().size(),"sourceEvidenceEnabled",panel.captureEvidence.isEnabled()));require(!panel.result.changes().isEmpty(),"Real account fields differ");
      save("jwt-analysis",obj("account","wb_alice","decoded",Core.jwt(actors.get(1).get("token").toString()),"signatureVerified",false));
    });
    test("FM-01","mapper","실제 관측 트래픽의 내장 Mapper 자동 묶음",()->{
      settleObservers();Object workspace=field(wb,"featureMapper"),mapper=field(workspace,"mapper");long count=(Long)invoke(mapper,"count",new Class<?>[]{});Object features=invoke(mapper,"featureSummaries",new Class<?>[]{});save("mapper",obj("packets",count,"features",features.toString(),"metadata",invoke(mapper,"metadata",new Class<?>[]{})));require(count>0,"Actual embedded mapper packets");
    });
    test("AC-01","activity","실제 관측 URL의 진행·재검토 관리",()->{
      settleObservers();require(!activity.tracker.views().isEmpty(),"Real activity observations");var view=activity.tracker.views().get(0);boolean prior=activity.tracker.completed(view.key());activity.tracker.complete(view.key(),true);activity.tracker.review(view.method(),view.url(),"로컬 은행 수동 확인");String saved=activity.tracker.save();save("activity",obj("observedBeforeManualComplete",prior,"items",activity.tracker.views().size(),"data",MinerJson.parse(saved)));
    });
    test("SC-01","inspection","기존 상단 통합 점검의 실제 은행 크롤·분류",()->{
      edt(()->SimpleWorkflowTest.click((JPanel)field(wb,"root"),"wb.navigation.primary.inspect"));ScopeInspectionPanel panel=(ScopeInspectionPanel)field(wb,"scopeInspectionPanel");
      edt(()->{panel.url.setText(base+"/");panel.refreshProfiles();panel.budget.setValue(100);panel.depth.setValue(1);panel.delay.setValue(10);panel.timeout.setValue(8);panel.browserMode.setSelected(false);panel.katanaMode.setSelected(false);panel.compareAccounts.setSelected(false);panel.startInspection();});
      await(()->Set.of(ScopeInspection.State.COMPLETED,ScopeInspection.State.PAUSED,ScopeInspection.State.FAILED,ScopeInspection.State.STOPPED).contains(panel.runner.snapshot().state()),120);
      edt(()->panel.accept(panel.runner.snapshot()));var snap=panel.dashboardSnapshot();save("scope-inspection",obj("runId",snap.id(),"state",snap.state().name(),"message",snap.message(),"sent",snap.sent(),"completed",snap.completed(),"pending",snap.pending(),"excluded",snap.excluded(),"failed",snap.failed(),"partial",snap.partial(),"findings",snap.findings().stream().map(f->obj("category",f.category(),"title",f.title(),"url",f.url(),"method",f.method(),"account",f.account(),"confidence",f.confidence(),"severity",f.severity(),"state",f.state(),"detail",f.detail(),"originalId",f.originalId())).toList(),"pages",snap.pages().stream().map(p->obj("originalId",p.original()==null?"":p.original().id(),"url",p.url(),"account",p.account(),"status",p.status(),"classification",p.classification())).toList()));
      ScopeInspectionReport.write(out.resolve("inspection-product-export"),snap);require(snap.sent()>0&&snap.completed()>0,"Real inspection requests and pages");
    });
    test("SC-02","dashboard","실행한 통합 점검과 동일한 결과 대시보드",()->{
      ScopeInspectionPanel panel=(ScopeInspectionPanel)field(wb,"scopeInspectionPanel");var snap=panel.dashboardSnapshot();edt(()->SimpleWorkflowTest.click((JPanel)field(wb,"root"),"wb.navigation.primary.dashboard"));ScopeDashboardPanel dashboard=(ScopeDashboardPanel)field(wb,"scopeDashboard");require(!snap.id().isBlank()&&dashboard.current.id().equals(snap.id()),"Dashboard uses same actual run");save("dashboard",obj("runId",dashboard.current.id(),"sameRun",true,"pages",snap.pages().size(),"findings",snap.findings().size()));
    });
    settleObservers();
    save("response-findings",recon.issues.values().stream().map(f->obj("rule",f.rule(),"url",f.url(),"location",f.location(),"evidence",f.evidence())).toList());
    Object finalMapper=field(field(wb,"featureMapper"),"mapper");save("mapper-final",obj("packets",invoke(finalMapper,"count",new Class<?>[]{}),"metadata",invoke(finalMapper,"metadata",new Class<?>[]{})));
    save("activity-final",obj("items",activity.tracker.views().size(),"data",MinerJson.parse(activity.tracker.save())));
    List<Object> finalJs=new ArrayList<>();for(var e:((JsSourceCache)field(js,"cache")).snapshot().entrySet())if(e.getValue().result()!=null){var r=e.getValue().result();finalJs.add(obj("url",e.getKey(),"status",r.status(),"endpoints",r.endpoints().stream().map(x->obj("url",x.url(),"method",x.method(),"complete",x.complete())).toList()));}save("js-analysis-final",finalJs);
    EnvironmentPanel finalEnvironment=(EnvironmentPanel)field(wb,"environments");save("environment-final",finalEnvironment.catalog.sites.values().stream().map(x->obj("origin",x.origin,"detections",x.detections.values().toString())).toList());
    ExposureResearchPanel finalExposure=(ExposureResearchPanel)field(wb,"exposureResearch");save("exposure-research-final",obj("observations",finalExposure.observations.toString(),"query",finalExposure.query.getText(),"cveResultRows",finalExposure.cveModel.getRowCount(),"externalProviderLookup","NOT_RUN: no external CVE/Google provider calls; loopback lab only"));
    save("measurement-stages",obj("finalStage","After all13 functional steps and completed Scope inspection; after capture-observation/recon/activity/JS worker drain, before native page capture","finalSnapshotFiles",List.of("mapper-final.json","activity-final.json","js-analysis-final.json","environment-final.json","exposure-research-final.json","response-findings.json","traffic.json","original-id-index.json"),"earlierSnapshots",obj("mapper.json","Before Scope inspection; feature-specific embedded Mapper snapshot","activity.json","Before Scope inspection; manual completion/review operation proof","js-analysis.json","After initial JS/HTTP analysis, before Scope inspection","environment.json","After explicit four-path scan, before Scope inspection"),"uniqueNetworkRequests",sequence.get(),"note","Mapper may observe the same real HTTP original through capture and Scope handoff. Mapping records, source references, findings and network requests overlap and must not be summed."));
    exportOriginals();
    save("traffic",obj("captured",((DiskTrafficStore)field(wb,"trafficStore")).size(),"transportRequests",sequence.get(),"received",field(wb,"received").toString(),"processingErrors",field(wb,"processingErrors").toString(),"scope",base+"/"));
    save("diagnostics",((Diagnostics)field(wb,"diagnostics")).snapshot().stream().map(e->obj("area",e.area(),"type",e.type(),"location",e.location())).toList());
    save("transport-index",transport.stream().sorted(Comparator.comparingInt(r->((Number)r.get("index")).intValue())).toList());
  }
  void exportOriginals()throws Exception{
    Map<String,DiskTrafficStore.PacketRef> originals=new LinkedHashMap<>();DiskTrafficStore store=(DiskTrafficStore)field(wb,"trafficStore");for(long i=0;i<store.size();i++){var ref=store.ref(i);originals.put(ref.id(),ref);}
    ScopeInspectionPanel panel=(ScopeInspectionPanel)field(wb,"scopeInspectionPanel");if(panel!=null)for(var page:panel.dashboardSnapshot().pages())if(page.original()!=null)originals.put(page.original().id(),page.original());
    Path folder=out.resolve("originals");Files.createDirectories(folder);List<Map<String,Object>> rows=new ArrayList<>();int index=0;
    for(var ref:originals.values()){String prefix=String.format(Locale.ROOT,"%04d",++index);byte[] q=ref.request().toByteArray().getBytes(),r=ref.response()==null?new byte[0]:ref.response().toByteArray().getBytes();Files.write(folder.resolve(prefix+".request.http"),q);Files.write(folder.resolve(prefix+".response.http"),r);rows.add(obj("originalId",ref.id(),"url",ref.url(),"status",ref.response()==null?0:(int)ref.response().statusCode(),"requestSHA256",sha(q),"responseSHA256",sha(r),"requestBytes",q.length,"responseBytes",r.length,"privateRequestFile","originals/"+prefix+".request.http","privateResponseFile","originals/"+prefix+".response.http"));}
    save("original-id-index",rows);
  }
  void finish()throws Exception{save("results",obj("version",BuildInfo.VERSION,"jarSHA256",EXPECTED_JAR,"site",base,"transport","Real loopback HTTP/1.1 via Java HTTP client and Montoya-compatible host adapter; normalized response headers, original body bytes.","nativeBurp",false,"nativeWindows",false,"syntheticTargetResponses",false,"checks",checks,"httpRequests",sequence.get(),"completed",true,"failedChecks",checks.stream().filter(c->c.get("status").equals("FAIL")).count()));}
  @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception{
    if(args.length<3)throw new IllegalArgumentException("BankSimpleAudit private-state.json private-output public-output [images-output]");
    BankSimpleAudit audit=new BankSimpleAudit();Map<String,Object> state=(Map<String,Object>)MinerJson.parse(Files.readString(Path.of(args[0])));audit.base=state.get("base").toString().replaceAll("/$","");require(Set.of("127.0.0.1","localhost","::1").contains(URI.create(audit.base).getHost()),"Loopback bank only");audit.actors=(List<Map<String,Object>>)state.get("actors");
    for(var actor:audit.actors)for(String key:List.of("token","password"))if(actor.get(key) instanceof String s&&!s.isEmpty())audit.secrets.add(s);if(state.get("password") instanceof String s&&!s.isEmpty())audit.secrets.add(s);
    audit.out=Path.of(args[1]);audit.published=Path.of(args[2]);Files.createDirectories(audit.out);Files.createDirectories(audit.published);
    Path product=Path.of(WorkbenchExtension.class.getProtectionDomain().getCodeSource().getLocation().toURI());require(Files.isRegularFile(product)&&sha(Files.readAllBytes(product)).equals(EXPECTED_JAR),"Exact immutable1.4.21 JAR");
    try{audit.run();audit.finish();if(args.length>3&&!java.awt.GraphicsEnvironment.isHeadless()){audit.wb.dialogOpener=WorkbenchUi::settingsWindow;ScopeInspectionPanel panel=(ScopeInspectionPanel)field(audit.wb,"scopeInspectionPanel");if(panel!=null)panel.settings.opener=audit.wb.dialogOpener;Class<?> capture=Class.forName("kr.workbench.BankWorkbenchCapture");capture.getMethod("setSecrets",Collection.class).invoke(null,audit.secrets);int before=audit.sequence.get();capture.getMethod("capture",WorkbenchExtension.class,Object.class,Path.class).invoke(null,audit.wb,audit.payload,Path.of(args[3]));require(audit.sequence.get()==before,"Capturing existing pages never sends additional target HTTP");}}
    finally{for(var h:PayloadIntegrationTest.modernUnloads)h.extensionUnloaded();for(var h:PayloadIntegrationTest.legacyUnloads)h.extensionUnloaded();edt(()->{for(var w:java.awt.Window.getWindows())w.dispose();});}
    long failed=audit.checks.stream().filter(c->c.get("status").equals("FAIL")).count();System.out.println("BANK_SIMPLE_FINISHED checks="+audit.checks.size()+" failed="+failed+" HTTP="+audit.sequence.get());System.exit(failed==0?0:1);
  }
}
