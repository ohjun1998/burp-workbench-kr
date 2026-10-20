package kr.workbench;

import burp.api.montoya.*;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Content negatives plus actual loopback discovery, identity gating, pause and durable metadata. */
public final class EnvironmentUpgradeTest extends WorkbenchTest {
  static String kind(String url,int code,String body){return EnvironmentContent.inspect(url,code,"",body,"").kind();}
  static void await(BooleanSupplier condition,String message)throws Exception{for(int i=0;i<500;i++){if(condition.getAsBoolean())return;Thread.sleep(10);}throw new AssertionError(message);}
  static EnvironmentScan.Options options(boolean baseline,int budget,int depth){return new EnvironmentScan.Options(baseline,true,Set.of(),0,3000,budget,depth,true,true,"",List.of());}
  static EnvironmentProject.Pending task(String origin,String path){return new EnvironmentProject.Pending(origin+path,0);}
  public static void main(String[] args)throws Exception{
    check(kind("https://x/config",200,"A=one\nAPI_KEY=TOP_SECRET").equals("ENV"),"configuration requires content, not filename");
    check(kind("https://x/.env",200,"<html><body>Home</body></html>").equals("REVIEW"),"HTML at env filename is not a config leak");
    check(kind("https://x/.env",403,"A=one\nAPI_KEY=TOP_SECRET").equals("DENIED"),"403 never establishes exposed content");
    check(kind("https://x/spec",200,"{\"openapi\":\"3.0.0\",\"paths\":{}}").equals("OPENAPI"),"OpenAPI JSON structure");
    check(kind("https://x/spec",200,"{\"openapi\":\"3.0.0\",\"paths\":[]}").equals("JSON"),"malformed paths not classified as specification");
    check(kind("https://x/",200,"<form><input type='password'></form>").equals("LOGIN"),"200 login form distinguished");
    check(kind("https://x/",200,"password field documentation").equals("REVIEW"),"password word alone is not a login screen");
    check(kind("https://x/",200,"<title>Index of /</title><a href='../'>Parent Directory</a>").equals("INDEX"),"directory listing requires two signals");
    check(kind("https://x/",200,"<title>Index of contents</title>").equals("REVIEW"),"title alone not a listing");
    check(kind("https://x/.git/HEAD",200,"ref: refs/heads/main\n").equals("GIT"),"Git HEAD format");
    check(kind("https://x/readme",200,"ref: refs/heads/main").equals("REVIEW"),"Git marker constrained to actual HEAD path");
    check(kind("https://x/a.map",200,"{\"version\":3,\"sources\":[],\"mappings\":\"\"}").equals("SOURCEMAP"),"source map structure");
    check(kind("https://x/",200,"<title>Just a moment...</title>cf-chl-test").equals("CHALLENGE"),"challenge detected");
    check(kind("https://x/",200,"x".repeat(EnvironmentContent.MAX+1)).equals("LIMIT"),"large response classification withheld");
    String a="<html><head><title>Not found</title></head><body><p>Request id: abcdef12345</p><span>2026-10-18T03:00:00Z</span></body></html>",b=a.replace("abcdef12345","987654fedcb").replace("03:00:00","03:00:01");
    var first=EnvironmentControls.sample(200,"Content-Type: text/html",a,"https://x/a",Set.of());var second=EnvironmentControls.sample(200,"Content-Type: text/html",b,"https://x/b",Set.of());
    var ref=EnvironmentControls.reference(first,second,true,"");check(ref.stable(),"dynamic trace and timestamp preserve stable HTML reference");
    String state=EnvironmentControls.state(200,"Content-Type: text/html",b,"https://x/b",ref,Set.of());check(state.contains("기준과 유사"),"similar response labeled for review rather than absent");
    check(!EnvironmentControls.state(200,"Content-Type: text/html","<html><body>Private payroll data</body></html>","https://x/b",ref,Set.of()).contains("기준과 유사"),"different business page remains reviewable");
    check(!EnvironmentContent.similar("JSON:{\"id\":1}","JSON:{\"id\":2}"),"JSON values never fuzzy suppressed");
    check(EnvironmentContent.inspect("https://x/a",200,"",b,state).kind().equals("SIMILAR"),"soft response classification precedes content findings");

    BountyFeaturesTest.factory();List<String> received=Collections.synchronizedList(new ArrayList<>());AtomicBoolean expired=new AtomicBoolean(),limited=new AtomicBoolean(),mutate=new AtomicBoolean();AtomicInteger value=new AtomicInteger(1);
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",x->{String path=x.getRequestURI().getPath();received.add(path);int code=200;String body="{\"data\":"+value.get()+"}";
      if(path.equals("/me")){boolean auth=x.getRequestHeaders().getFirst("Cookie")!=null&&!expired.get();code=auth?200:401;body=auth?"{\"id\":\"alice\"}":"{}";}
      else if(path.equals("/root"))body="<html><a href='/spec'>schema</a><a href='https://outside.invalid/secret'>outside</a><a href='/logout'>logout</a></html>";
      else if(path.equals("/spec"))body="{\"openapi\":\"3.0.0\",\"paths\":{\"/data\":{\"get\":{}},\"/write\":{\"post\":{}}}}";
      else if(path.equals("/rate")&&limited.get()){code=429;body="rate";x.getResponseHeaders().set("Retry-After","0");}
      else if(path.contains("wb-missing-"))body="<html><body>missing</body></html>";
      byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",body.startsWith("{")?"application/json":"text/html");x.sendResponseHeaders(code,bytes.length);x.getResponseBody().write(bytes);x.close();});server.start();
    String origin="http://127.0.0.1:"+server.getAddress().getPort();var client=java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
    var http=proxy(Http.class,(p,m,v)->{if(!m.getName().equals("sendRequest"))return def(m);HttpRequest q=(HttpRequest)v[0];check(!javax.swing.SwingUtilities.isEventDispatchThread(),"network runs outside EDT");var outgoing=java.net.http.HttpRequest.newBuilder(URI.create(q.url())).GET();for(var h:q.headers())if(!h.name().equalsIgnoreCase("Host"))outgoing.header(h.name(),h.value());var r=client.send(outgoing.build(),java.net.http.HttpResponse.BodyHandlers.ofString());var hs=new ArrayList<burp.api.montoya.http.message.HttpHeader>();r.headers().map().forEach((k,vs)->vs.forEach(s->hs.add(header(k,s))));return AuthRecordTest.rr(mutate.get()?BountyFeaturesTest.rq(origin+"/changed","GET","",q.headers()):q,BountyFeaturesTest.rs(r.statusCode(),r.body(),hs));});
    var api=proxy(MontoyaApi.class,(p,m,v)->m.getName().equals("http")?http:def(m));var scope=WorkbenchScope.install(api);scope.add(origin+"/",true);
    var anonymous=new EnvironmentScan.Profile("anonymous",Map.of(),null);List<EnvironmentScan.Result> results=new CopyOnWriteArrayList<>();
    try{
      var scan=new EnvironmentScan(api,options(false,30,2),anonymous,List.of(task(origin,"/root")),results::add,()->false);scan.run();
      check(received.containsAll(List.of("/root","/spec","/data")),"verified response feeds OpenAPI then concrete GET route");
      check(!received.contains("/logout")&&!received.contains("/write"),"followup skips logout and write-only declaration");
      check(received.size()==3&&scan.remaining().isEmpty(),"depth and dedup close discovery queue");
      check(results.stream().anyMatch(r->r.finding().kind().equals("OPENAPI")),"actual fetched specification classified");
      received.clear();results.clear();scan=new EnvironmentScan(api,options(true,2,0),anonymous,List.of(task(origin,"/data")),results::add,()->false);scan.run();
      check(received.size()==2&&received.stream().allMatch(u->u.contains("wb-missing-")),"request budget includes both missing controls");
      check(scan.remaining().size()==1&&results.isEmpty(),"budget retains unsent candidate for continuation");
      limited.set(true);received.clear();scan=new EnvironmentScan(api,options(false,10,0),anonymous,List.of(task(origin,"/rate"),task(origin,"/data")),results::add,()->false);final var rate=scan;Thread worker=new Thread(rate::run);worker.start();await(()->rate.paused,"429 pause");
      check(received.equals(List.of("/rate"))&&rate.remaining().size()==2,"429 pauses without losing current or remaining route");Thread.sleep(80);check(received.size()==1,"paused scan sends no requests");limited.set(false);rate.resume(anonymous);worker.join(4000);check(!worker.isAlive()&&received.equals(List.of("/rate","/rate","/data")),"explicit resume retries limited route then continues");
      check(EnvironmentScan.retryTime("Retry-After: 3")>System.currentTimeMillis()+2000,"Retry-After seconds honored");
      var config=new CookieSessionProbe.Config(origin+"/me","$.id","alice");var authenticated=new EnvironmentScan.Profile("slot-0",Map.of("cookie","SID=VERY_SECRET"),config);
      expired.set(true);received.clear();scan=new EnvironmentScan(api,options(false,20,0),authenticated,List.of(task(origin,"/data")),results::add,()->false);final var auth=scan;worker=new Thread(auth::run);worker.start();await(()->auth.paused,"expired identity pause");
      check(!received.contains("/data"),"expired identity blocks target discovery");
      rejects(()->auth.resume(new EnvironmentScan.Profile("slot-1",authenticated.headers(),config)),"resume cannot switch account");
      expired.set(false);auth.resume(authenticated);worker.join(4000);check(!worker.isAlive()&&received.contains("/data"),"refreshed identity with anonymous control allows explicit resume");
      received.clear();scan=new EnvironmentScan(api,options(false,1,0),authenticated,List.of(task(origin,"/data")),results::add,()->false);scan.run();check(received.size()==1&&!received.contains("/data")&&scan.remaining().size()==1,"identity requests also consume request budget");
      mutate.set(true);results.clear();scan=new EnvironmentScan(api,options(false,10,0),anonymous,List.of(task(origin,"/data")),results::add,()->false);scan.run();check(results.get(0).finding().kind().equals("ERROR"),"changed actual request cannot yield content finding");mutate.set(false);
      var paused=new EnvironmentScan(api,options(false,10,0),anonymous,List.of(task(origin,"/data")),results::add,()->false);paused.pause("manual",0);received.clear();worker=new Thread(paused::run);worker.start();Thread.sleep(50);check(received.isEmpty(),"manual pause gates first request");paused.stop();worker.join(2000);check(!worker.isAlive()&&paused.remaining().size()==1,"stop wakes paused worker and preserves queue");

      EnvironmentProject project=new EnvironmentProject();String hash=EnvironmentProject.hash("normalized body");check(project.add(origin+"/data","anonymous",200,"JSON",hash).equals("신규"),"first result is new");check(project.add(origin+"/data","anonymous",200,"JSON",hash).equals("동일"),"same response stable");check(project.add(origin+"/data","anonymous",200,"JSON",EnvironmentProject.hash("different")).equals("내용 변경"),"body change tracked");check(project.add(origin+"/data","anonymous",403,"DENIED",hash).equals("접근 변경"),"access change tracked");check(project.add(origin+"/data","slot-0",200,"JSON",hash).equals("신규"),"account history isolated");
      project.candidate(origin+"/spec","응답 참조");project.pending=List.of(task(origin,"/data"));String json=project.json();check(!json.contains("VERY_SECRET")&&!json.contains("normalized body")&&!json.contains("Cookie"),"project contains metadata without credentials or bodies");
      Path file=Files.createTempFile("wb-env-test-",".json");try{project.write(file);var loaded=EnvironmentProject.read(file);check(loaded.history.size()==5&&loaded.pending.size()==1&&loaded.candidates.size()==1,"project atomic roundtrip preserves histories and pending queue");String before=Files.readString(file);project.pendingAccount="bad";rejects(()->EnvironmentProject.parse(project.json()),"invalid account rejected before import");project.pendingAccount="anonymous";check(Files.readString(file).equals(before),"invalid import leaves saved file unchanged");}finally{Files.deleteIfExists(file);}
      rejects(()->new EnvironmentProject.Pending(origin+"/data?token=SECRET",0),"project cannot retain query credentials");rejects(()->new EnvironmentProject.Pending(origin+"/../data",0),"project rejects traversal paths");rejects(()->EnvironmentProject.parse(json.replace("\"schema\":1","\"schema\":4294967297")),"schema integer overflow rejected");
      AtomicReference<EnvironmentPanel> panel=new AtomicReference<>();int before=received.size();edt(()->{var p=new EnvironmentPanel(api);p.applyProject(EnvironmentProject.parse(json));panel.set(p);check(p.paths.getRowCount()>0&&p.remaining.isEnabled(),"loaded project restores candidates and resume action");});check(received.size()==before,"loading project never sends requests");edt(panel.get()::close);
    }finally{WorkbenchScope.release(api);server.stop(0);}
    AtomicInteger portChecks=new AtomicInteger();var defaultHttp=proxy(Http.class,(p,m,v)->{if(!m.getName().equals("sendRequest"))return def(m);portChecks.incrementAndGet();HttpRequest q=(HttpRequest)v[0];boolean identity=q.url().endsWith("/me"),auth=q.headerValue("Cookie")!=null;return AuthRecordTest.rr(q,BountyFeaturesTest.rs(identity&&!auth?401:200,identity&&auth?"{\"id\":\"alice\"}":"{}",List.of(header("Content-Type","application/json"))));});
    var defaultApi=proxy(MontoyaApi.class,(p,m,v)->m.getName().equals("http")?defaultHttp:def(m));
    var defaultProfile=new EnvironmentScan.Profile("slot-0",Map.of("cookie","SID=secret"),new CookieSessionProbe.Config("https://default.test/me","$.id","alice"));
    var defaultScan=new EnvironmentScan(defaultApi,options(false,10,0),defaultProfile,List.of(task("https://default.test","/data")),r->{},()->false);defaultScan.run();
    check(portChecks.get()==3&&defaultScan.remaining().isEmpty(),"implicit HTTPS 443 uses same identity origin representation");
    check(EnvironmentResponse.signature("<html><body><a href='/b'>x</a></body></html>","https://x/b").contains("</body>"),"short reflected path cannot corrupt HTML closing tags");
    check(EnvironmentScan.retryTime("Retry-After: 9999999999999999999999")==Long.MAX_VALUE,"large Retry-After cannot overflow into early retry");
    long tomorrow=System.currentTimeMillis()+3*86400000L;String date=java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(tomorrow).atZone(java.time.ZoneOffset.UTC));
    check(EnvironmentScan.retryTime("Retry-After: "+date)>System.currentTimeMillis()+2*86400000L,"HTTP date Retry-After is not shortened to one day");
    var feedback=new EnvironmentCatalog();var observed=feedback.observe("https://score.test/","","");EnvironmentDiscovery.importWords(observed,"/admin","list");feedback.feedback("https://score.test/admin",200,"HTTP 200 · 없는 경로 기준과 유사");
    check(feedback.candidates(observed,"",false,300).get(0).score()<40,"similar fallback response cannot boost discovery priority");
    feedback.feedback("https://score.test/admin",200,"HTTP 200 · 로그인 화면");check(feedback.candidates(observed,"",false,300).get(0).score()<40,"login screen cannot boost successful discovery priority");
    AtomicReference<EnvironmentPanel> immediate=new AtomicReference<>();edt(()->{var p=new EnvironmentPanel(defaultApi);immediate.set(p);p.compareMissing.setSelected(false);EnvironmentBatchTest.fill(p,"https://default.test/data");p.start();check(p.scan!=null,"pause/stop worker exists before start returns");p.stop();check(p.scan.stopped,"immediate stop reaches worker before background scheduling");});EnvironmentBatchTest.idle(immediate.get());edt(immediate.get()::close);
    System.out.println("EnvironmentUpgradeTest: "+checks+" checks passed");
  }
}
