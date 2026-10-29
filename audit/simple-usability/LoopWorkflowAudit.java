package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.repeater.Repeater;
import java.awt.Component;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;
import javax.swing.*;

/** Production Simple UI + real, original LOOP3.10.0 HTTP. Not native Burp GUI. */
public final class LoopWorkflowAudit extends WorkbenchTest {
  static final String BASE="http://127.0.0.1:18888";
  static final List<Map<String,Object>> assertions=new CopyOnWriteArrayList<>(), phases=new CopyOnWriteArrayList<>(), transport=new CopyOnWriteArrayList<>(), runs=new CopyOnWriteArrayList<>();
  static final AtomicInteger calls=new AtomicInteger(), loginCalls=new AtomicInteger();
  static Map<String,Object> map(Object... values){var result=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)result.put(values[i].toString(),values[i+1]);return result;}
  static void require(boolean condition,String label){assertions.add(map("name",label,"passed",condition));if(!condition)throw new AssertionError(label);}
  static Object value(Object owner,String field){try{return get(owner,field);}catch(Exception e){throw new RuntimeException(e);}}
  static void waitFor(BooleanSupplier condition,String label)throws Exception{long end=System.nanoTime()+Duration.ofSeconds(35).toNanos();while(System.nanoTime()<end){edt(()->{});if(condition.getAsBoolean())return;Thread.sleep(20);}throw new AssertionError("Timeout: "+label);}
  static void stage(String name,Runnable action)throws Exception{int before=calls.get();long start=System.nanoTime();edt(action);edt(()->{});phases.add(map("name",name,"httpDelta",calls.get()-before,"elapsedMillis",(System.nanoTime()-start)/1e6));}
  static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
  static boolean terminal(ScopeInspection.State state){return Set.of(ScopeInspection.State.COMPLETED,ScopeInspection.State.STOPPED,ScopeInspection.State.FAILED,ScopeInspection.State.CLOSED).contains(state);}
  static String safeUrl(String url){URI uri=URI.create(url);return EnvironmentCatalog.origin(uri)+Objects.toString(uri.getRawPath(),"/")+(uri.getRawQuery()==null?"":"?[query omitted]");}
  static boolean hasCookie(HttpRequest request,String cookie){return Objects.equals(request.headerValue("Cookie"),cookie);}

  record Login(String cookie,String expectedUser){}
  static Login login(String username)throws Exception{
    var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    var client=HttpClient.newBuilder().cookieHandler(cookies).version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    String body="username="+username+"&password="+java.net.URLEncoder.encode("LabOnly!2026",StandardCharsets.UTF_8);
    var request=java.net.http.HttpRequest.newBuilder(URI.create(BASE+"/api/auth/login")).header("Content-Type","application/x-www-form-urlencoded;charset=UTF-8").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
    var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());loginCalls.incrementAndGet();
    require(response.statusCode()==200,"normal seed login HTTP200 for "+username);
    String cookie=cookies.getCookieStore().getCookies().stream().map(c->c.getName()+"="+c.getValue()).reduce((a,b)->a+"; "+b).orElse("");
    require(!cookie.isEmpty(),"login obtains an isolated session in memory for "+username);
    return new Login(cookie,username);
  }

  public static void main(String[] args)throws Exception{
    if(args.length!=2)throw new IllegalArgumentException("Usage: LoopWorkflowAudit output-directory jarSHA256");
    Path output=Path.of(args[0]);Files.createDirectories(output);long started=System.currentTimeMillis();String startedAt=Instant.now().toString();
    var metadata=new LinkedHashMap<String,Object>();metadata.putAll(map("productionVersion",BuildInfo.VERSION,"jarSHA256",args[1],"startedAt",startedAt,"baseURL",BASE,"sourceCommit","81b48defb81db7c656e913a8ebd6848e30621cf1","nativeBurp",false,"browserDOM",false,"scope","Production WorkbenchExtension, inspection and dashboard Swing components; real original LOOP3.10.0 HTTP through Montoya test adapter. No native Burp desktop, OS clicks, file picker, browser extension, Katana or Docker exercised."));
    BurpExtender entry=null;WorkbenchExtension extension=null;ScopeInspectionPanel inspection=null;Path reportPath=output.resolve("private-report.zip");
    try{
      Login alice=login("alice");
      var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
      Http http=proxy(Http.class,(p,method,arguments)->{
        if(!method.getName().equals("sendRequest"))return def(method);
        HttpRequest request=(HttpRequest)arguments[0];
        if(SwingUtilities.isEventDispatchThread())throw new AssertionError("Target HTTP must never run on EDT");
        if(!EnvironmentCatalog.origin(URI.create(request.url())).equals(BASE))throw new AssertionError("Origin boundary violation");
        if(!WorkbenchScope.contains((MontoyaApi)ApiHolder.api,request.url()))throw new AssertionError("SCOPE boundary violation");
        if(!Set.of("GET","HEAD").contains(request.method()))throw new AssertionError("Unexpected non-read inspection request");
        int index=calls.incrementAndGet();long began=System.nanoTime();
        var builder=java.net.http.HttpRequest.newBuilder(URI.create(request.url())).timeout(Duration.ofSeconds(5)).method(request.method(),java.net.http.HttpRequest.BodyPublishers.noBody());
        for(HttpHeader h:request.headers())if(!Set.of("host","connection","content-length","upgrade","http2-settings").contains(h.name().toLowerCase(Locale.ROOT)))builder.header(h.name(),h.value());
        var received=client.send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());var headers=new ArrayList<HttpHeader>();received.headers().map().forEach((name,values)->values.forEach(v->headers.add(header(name,v))));
        var response=ScopeInspectionFixture.wireResponse(received.statusCode(),headers,received.body());registerTrafficPacket(request,response);
        transport.add(map("index",index,"url",safeUrl(request.url()),"method",request.method(),"status",received.statusCode(),"account",hasCookie(request,alice.cookie())?"alice":"anonymous","elapsedMillis",(System.nanoTime()-began)/1e6));
        return proxy(HttpRequestResponse.class,(pp,mm,aa)->switch(mm.getName()){case "request"->request;case "response"->response;default->def(mm);});
      });
      var fallback=SimpleWorkflowTest.previewApi();var repeated=new AtomicReference<HttpRequest>();var repeater=proxy(Repeater.class,(p,m,a)->{if(m.getName().equals("sendToRepeater"))repeated.set((HttpRequest)a[0]);return def(m);});
      MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->switch(m.getName()){case "http"->http;case "repeater"->repeater;default->m.invoke(fallback,a);});ApiHolder.api=api;
      ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
      entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));BurpExtender owner=entry;
      stage("load production extension",()->owner.initialize(api));
      extension=(WorkbenchExtension)value(entry,"workbench");WorkbenchExtension active=extension;
      var root=(JPanel)value(extension,"root");var dashboard=(ScopeDashboardPanel)value(extension,"scopeDashboard");var evidence=(BountySupportPanel)value(extension,"bountySupport");var dialog=new AtomicReference<Component>();
      active.dialogOpener=(component,title,body,modal,closed)->{dialog.set(body);return null;};
      stage("open dashboard and inspection without sending",()->{SimpleWorkflowTest.click(root,"wb.simple.dashboard.open");SimpleWorkflowTest.click(dashboard,"wb.scope.dashboard.inspect");});
      inspection=(ScopeInspectionPanel)value(extension,"scopeInspectionPanel");ScopeInspectionPanel panel=inspection;
      require(calls.get()==0,"loading dashboard and inspection sends zero target HTTP");ScopeInspectionFixture.installFactory();
      stage("configure SCOPE account and explicit run controls",()->{
        ((WorkbenchScope)value(active,"localScope")).add(BASE+"/",true);
        var auth=(AuthComparePanel)value(active,"authCompare");var candidate=new AuthComparePanel.Candidate(1,"loop-alice-memory",BASE,Map.of("Cookie",alice.cookie()),1,"");
        auth.credentials.put(candidate.key(),candidate);auth.accounts.put(candidate.key(),new AuthAccount("Alice","alice","1","USER",""));auth.assign(0,candidate);auth.profileNames[0].setText("Alice");auth.sessionChecks[0]=new CookieSessionProbe.Config(BASE+"/api/access/me","$.user.USERNAME","alice");
        panel.refreshTargets();panel.url.setText(BASE+"/v3/api-docs");panel.refreshProfiles();panel.compareAccounts.setSelected(true);panel.budget.setValue(40);panel.depth.setValue(1);panel.delay.setValue(150);panel.timeout.setValue(5);panel.browserMode.setSelected(false);panel.katanaMode.setSelected(false);
      });
      require(panel.profile.getItemCount()==2,"UI offers anonymous and the real normal-login account");
      int firstStart=calls.get();stage("explicit inspection start",()->panel.start.doClick(0));
      waitFor(()->calls.get()-firstStart>=3,"first real reads");String firstRun=panel.runner.snapshot().id();
      stage("pause running inspection",()->panel.pause.doClick(0));waitFor(()->panel.runner.snapshot().state()==ScopeInspection.State.PAUSED,"paused runner");Thread.sleep(350);int paused=calls.get();Thread.sleep(300);edt(()->{});
      require(calls.get()==paused,"paused inspection emits no additional target requests after in-flight completion");
      stage("resume same inspection",()->panel.resume.doClick(0));waitFor(()->calls.get()>paused,"resumed reads");require(panel.runner.snapshot().id().equals(firstRun),"resume keeps the same run ID");
      stage("explicitly stop partial inspection",()->panel.stop.doClick(0));waitFor(()->panel.runner.snapshot().state()==ScopeInspection.State.STOPPED,"stopped runner");Thread.sleep(350);int stopped=calls.get();Thread.sleep(300);edt(()->{});
      require(calls.get()==stopped,"stopped inspection emits no additional target requests");
      runs.add(map("id",firstRun,"budget",40,"sent",panel.runner.snapshot().sent(),"actualRequests",calls.get()-firstStart,"terminalState",panel.runner.snapshot().state().name(),"pauseResumeStop",true));
      require(calls.get()-firstStart<=40,"first run remains inside its 40-request budget");
      stage("configure bounded second run",()->{panel.budget.setValue(30);panel.depth.setValue(0);panel.delay.setValue(0);});
      int secondStart=calls.get();stage("start bounded follow-up inspection",()->panel.start.doClick(0));
      waitFor(()->terminal(panel.runner.snapshot().state()),"bounded completion");Thread.sleep(250);edt(()->{});ScopeInspection.Snapshot snapshot=panel.runner.snapshot();
      waitFor(()->panel.current.id().equals(snapshot.id())&&terminal(panel.current.state())&&!dashboard.aggregating,"publish terminal snapshot");
      require(snapshot.state()==ScopeInspection.State.COMPLETED,"bounded second run completes");
      require(snapshot.sent()<=30&&calls.get()-secondStart<=30,"second run obeys the 30-request budget");
      require(snapshot.pages().stream().anyMatch(p->p.original()!=null),"real LOOP responses retain original IDs");
      runs.add(map("id",snapshot.id(),"budget",30,"sent",snapshot.sent(),"actualRequests",calls.get()-secondStart,"terminalState",snapshot.state().name(),"pending",snapshot.pending(),"partial",snapshot.partial(),"failed",snapshot.failed(),"completedMeansWholeSiteVerified",false));
      require(dashboard.current.id().equals(snapshot.id()),"inspection listener and dashboard retain the same real run ID");
      int handoffStart=calls.get();
      stage("dashboard path card and account drill-down",()->{
        SimpleWorkflowTest.click(root,"wb.simple.dashboard.open");SimpleWorkflowTest.click(dashboard,"wb.scope.dashboard.card.paths");
        boolean chosen=false;for(int i=0;i<dashboard.account.getItemCount();i++)if(dashboard.account.getItemAt(i).value().contains("Alice")){dashboard.account.setSelectedIndex(i);chosen=true;break;}require(chosen,"dashboard offers the actual login account");
      });
      waitFor(()->!dashboard.aggregating&&dashboard.table.getRowCount()>0,"filtered real originals");
      var selected=dashboard.selectedRow();require(selected!=null&&!selected.originalId().isEmpty(),"filtered selection carries an exact immutable original ID");
      var original=snapshot.pages().stream().map(ScopeInspection.Page::original).filter(Objects::nonNull).filter(p->p.id().equals(selected.originalId())).findFirst().orElseThrow();
      require(hasCookie(original.request(),alice.cookie()),"selected account resolves its exact normal-login original bytes");
      stage("open exact original inspector",()->SimpleWorkflowTest.click(dashboard,"wb.scope.dashboard.original"));
      require(dialog.get() instanceof JComponent c&&"wb.scope.inspection.original".equals(c.getName()),"original handoff opens production inspector content");
      require(SimpleWorkflowTest.visibleComponents(dialog.get()).stream().anyMatch(c->c instanceof JLabel label&&label.getText().contains(original.id())),"inspector displays selected original ID");
      stage("prepare selected original in Repeater",()->SimpleWorkflowTest.click(dashboard,"wb.scope.dashboard.repeater"));
      require(repeated.get()==original.request(),"Repeater preparation receives the exact selected request object");
      stage("add selected original as evidence",()->SimpleWorkflowTest.click(dashboard,"wb.scope.dashboard.evidence"));
      require(evidence.evidence.stream().anyMatch(item->item.note().contains(original.id())&&item.request().contains(alice.cookie())),"evidence links exact original ID and request bytes");
      stage("return to retained filtered dashboard",()->SimpleWorkflowTest.click(root,"wb.simple.dashboard.open"));
      require(dashboard.selectedRow()!=null&&dashboard.selectedRow().originalId().equals(original.id()),"dashboard round trip preserves filtered selection");
      require(calls.get()==handoffStart,"dashboard filtering original Repeater and evidence handoffs send zero target HTTP");
      stage("save report using supplied temporary destination",()->panel.saveReport(reportPath));
      waitFor(()->Files.exists(reportPath)&&!((Boolean)value(panel,"reportBusy")),"report ZIP completes");
      int zipEntries;try(var zip=new ZipFile(reportPath.toFile())){zipEntries=zip.size();require(zipEntries>0,"production report creates a readable ZIP");}
      long zipBytes=Files.size(reportPath);String zipSha=sha(Files.readAllBytes(reportPath));Files.delete(reportPath);
      metadata.put("report",map("entries",zipEntries,"bytes",zipBytes,"sha256",zipSha,"temporaryZipRemoved",!Files.exists(reportPath),"destinationPickerExercised",false));
      require(calls.get()==handoffStart,"local report export sends zero target HTTP");
      var cardCounts=new LinkedHashMap<String,Object>();for(var item:dashboard.cards.entrySet())cardCounts.put(item.getKey().name(),item.getValue().count);
      metadata.put("finalSnapshot",map("id",snapshot.id(),"state",snapshot.state().name(),"pages",snapshot.pages().size(),"findings",snapshot.findings().size(),"partial",snapshot.partial(),"failed",snapshot.failed(),"selectedOriginalId",original.id(),"selectedAccount",selected.account(),"cardsForSelectedAccount",cardCounts));
      metadata.put("originals",snapshot.pages().stream().filter(p->p.original()!=null).map(p->map("id",p.original().id(),"method",p.method(),"url",safeUrl(p.original().url()),"status",p.status(),"account",p.account())).toList());
      require(PayloadIntegrationTest.sent.get()==0,"no legacy send occurs during UI preparation");
      int beforeUnload=calls.get();long unloadBegan=System.nanoTime();SimpleWorkflowTest.unload();phases.add(map("name","unload production extension","httpDelta",calls.get()-beforeUnload,"elapsedMillis",(System.nanoTime()-unloadBegan)/1e6));entry=null;Thread.sleep(500);edt(()->{});
      require(calls.get()==beforeUnload,"extension unload leaves no further target transmission");metadata.put("result","PASS");
    }catch(Throwable error){metadata.put("result","FAIL");metadata.put("failureClass",error.getClass().getSimpleName());metadata.put("failureLabel",Objects.toString(error.getMessage(),""));throw error;}
    finally{
      if(entry!=null){try{SimpleWorkflowTest.unload();}catch(Exception ignored){}}
      Files.deleteIfExists(reportPath);
      metadata.putAll(map("finishedAt",Instant.now().toString(),"elapsedMillis",System.currentTimeMillis()-started,"passed",assertions.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count(),"failed",assertions.stream().filter(r->!Boolean.TRUE.equals(r.get("passed"))).count(),"loginSetupHttpRequests",loginCalls.get(),"engineHttpRequests",calls.get(),"assertions",assertions,"phases",phases,"runs",runs,"transport",transport,"authConfigFilesWritten",false,"credentialsInPublicEvidence",false,"loginSetupInEngineBudget",false,"requestAndResponseEvidence","Only method, redacted-path URL, status, account label and immutable original IDs; no headers or bodies recorded. Private product runtime is removed by launcher.","performanceInterpretation","Stage durations are single automated observations on this machine, not native GUI latency or a cross-version benchmark."));
      Files.writeString(output.resolve("summary.json"),MetadataExport.json(metadata)+"\n",StandardCharsets.UTF_8);
      System.out.println(MetadataExport.json(map("result",metadata.get("result"),"version",BuildInfo.VERSION,"passed",metadata.get("passed"),"failed",metadata.get("failed"),"engineHttpRequests",calls.get(),"elapsedMillis",metadata.get("elapsedMillis"))));
    }
  }
  static final class ApiHolder{static volatile MontoyaApi api;}
}
