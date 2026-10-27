package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.net.ssl.*;

/** Opt-in real Katana 1.8.0 process + production proxy/coordinator. Never starts a native Burp GUI. */
public final class ScopeKatanaNativeHarness extends WorkbenchTest {
  static final String BINARY_SHA256="3214c11a0548667b37ec17bbb691706b7e0de1f0f24b3ead2ee12c301ebc1bbc";
  static final String ARCHIVE_SHA256="cb027ae99d0757061b70c07a15c7a80f62c5a535ab52c4a329f80c4b0a7309d2";
  static final String SESSION="SID=NATIVE_ONLY_AUTH_VALUE_0123456789abcdef";
  static final String API_SECRET="NATIVE_DEMO_API_PRIVATE_0123456789abcdef";
  static final String DB_PASSWORD="NATIVE_DB_PRIVATE_0123456789abcdef";
  static final List<String> PRIVATE_VALUES=List.of(SESSION,SESSION.substring(4),API_SECRET,DB_PASSWORD);
  static final List<Map<String,Object>> DIAGNOSTICS=new CopyOnWriteArrayList<>();

  static void await(BooleanSupplier condition,String note) throws Exception {
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(55);
    while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(15);}
    throw new AssertionError("Native Katana timeout: "+note);
  }
  static boolean terminal(ScopeInspection.Snapshot snapshot){return Set.of(ScopeInspection.State.COMPLETED,ScopeInspection.State.STOPPED,ScopeInspection.State.FAILED,ScopeInspection.State.CLOSED).contains(snapshot.state());}
  static boolean nativeTerminal(ScopeKatana.Snapshot snapshot){return Set.of("COMPLETED","PARTIAL","FAILED","STOPPED","CLOSED").contains(snapshot.state());}
  static boolean privateFree(String text){return PRIVATE_VALUES.stream().noneMatch(text::contains);}
  static String safeText(String value){String clean=Objects.requireNonNullElse(value,"");for(String token:PRIVATE_VALUES)clean=clean.replace(token,"[REDACTED]");return clean.substring(0,Math.min(500,clean.length()));}
  static String sha(Path path) throws Exception {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");
    try(var input=Files.newInputStream(path)){byte[] buffer=new byte[64*1024];for(int count;(count=input.read(buffer))>=0;)digest.update(buffer,0,count);}
    return HexFormat.of().formatHex(digest.digest());
  }
  static void reply(HttpExchange exchange,int code,String type,String text) throws IOException {
    byte[] body=text.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type);
    exchange.getResponseHeaders().set("Server","nginx/1.25 native-fixture");
    exchange.getResponseHeaders().set("X-Powered-By","Apache Tomcat/10.1 native-fixture");
    if(exchange.getRequestMethod().equals("HEAD")){exchange.getResponseHeaders().set("Content-Length",Integer.toString(body.length));exchange.sendResponseHeaders(code,-1);}
    else{exchange.sendResponseHeaders(code,body.length);exchange.getResponseBody().write(body);}exchange.close();
  }

  record Visit(String method,String path,String account,boolean tls){}
  static final class Fixture implements AutoCloseable {
    final List<Visit> visits=new CopyOnWriteArrayList<>(),trapVisits=new CopyOnWriteArrayList<>();
    final AtomicInteger revision=new AtomicInteger(1);
    final ExecutorService workers=Executors.newCachedThreadPool(task->{Thread thread=new Thread(task,"katana-native-fixture");thread.setDaemon(true);return thread;});
    final HttpServer http,trap;final HttpsServer https;final String httpOrigin,httpsOrigin,trapOrigin;
    final MontoyaApi api;
    Fixture() throws Exception {
      ScopeInspectionFixture.installFactory();
      http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);trap=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);
      https=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),16);
      var material=ScopeKatanaProxy.tlsFor("127.0.0.1");https.setHttpsConfigurator(new HttpsConfigurator(material.context()));
      httpOrigin="http://127.0.0.1:"+http.getAddress().getPort();httpsOrigin="https://127.0.0.1:"+https.getAddress().getPort();trapOrigin="http://127.0.0.1:"+trap.getAddress().getPort();
      http.setExecutor(workers);https.setExecutor(workers);trap.setExecutor(workers);
      http.createContext("/",this::serve);https.createContext("/",this::serve);
      trap.createContext("/",exchange->{trapVisits.add(visit(exchange));reply(exchange,200,"text/plain","other-port sentinel must not be visited");});
      http.start();https.start();trap.start();
      KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);trust.setCertificateEntry("native-fixture-only",material.certificate());
      TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(trust);
      SSLContext clientTls=SSLContext.getInstance("TLS");clientTls.init(null,factory.getTrustManagers(),null);
      var client=java.net.http.HttpClient.newBuilder().sslContext(clientTls).followRedirects(java.net.http.HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();
      Http transport=proxy(Http.class,(p,m,args)->{
        if(!m.getName().equals("sendRequest"))return def(m);HttpRequest request=(HttpRequest)args[0];
        if(javax.swing.SwingUtilities.isEventDispatchThread())throw new AssertionError("Native transport must not send from EDT");
        if(!request.url().startsWith(httpOrigin+"/")&&!request.url().startsWith(httpsOrigin+"/"))throw new AssertionError("Native transport attempted another Origin");
        if(!Set.of("GET","HEAD").contains(request.method()))throw new AssertionError("Native transport attempted a write method");
        var builder=java.net.http.HttpRequest.newBuilder(URI.create(request.url())).timeout(Duration.ofSeconds(5)).method(request.method(),java.net.http.HttpRequest.BodyPublishers.noBody());
        for(HttpHeader header:request.headers())if(!Set.of("host","connection","content-length","upgrade","http2-settings").contains(header.name().toLowerCase(Locale.ROOT)))builder.header(header.name(),header.value());
        var received=client.send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());var headers=new ArrayList<HttpHeader>();
        received.headers().map().forEach((name,values)->values.forEach(value->headers.add(header(name,value))));
        var response=ScopeInspectionFixture.wireResponse(received.statusCode(),headers,received.body());registerTrafficPacket(request,response);
        return proxy(HttpRequestResponse.class,(pp,mm,aa)->switch(mm.getName()){case "request"->request;case "response"->response;default->def(mm);});
      });
      MontoyaApi fallback=SimpleWorkflowTest.previewApi();api=proxy(MontoyaApi.class,(p,m,args)->m.getName().equals("http")?transport:m.invoke(fallback,args));
      WorkbenchScope.install(api).add(httpOrigin+"/app/",false);WorkbenchScope.current(api).add(httpsOrigin+"/app/",false);
    }
    Visit visit(HttpExchange exchange){String cookie=exchange.getRequestHeaders().getFirst("Cookie");return new Visit(exchange.getRequestMethod(),exchange.getRequestURI().getRawPath(),cookie==null?"anonymous":SESSION.equals(cookie)?"Alice":"unexpected-credential",exchange instanceof com.sun.net.httpserver.HttpsExchange);}
    String html(){return "<!doctype html><html><title>Native Katana fixture</title><nav>"
        +"<a href=/app/native-menu>Unquoted native menu</a><a href=/app/private>Private documents</a>"
        +"<a href=/app/redirect>Redirect</a><a href=/outside/sensitive>Outside path</a>"
        +"<a href=/app/excluded/private>Excluded path</a><a href="+trapOrigin+"/app/other-port>Another origin port</a>"
        +"<button hx-get=/app/native-htmx>Read fragment</button><button hx-post=/app/native-post>Write fragment</button>"
        +"<form action=/app/native-form method=post><input name=q><button>Search form</button></form>"
        +"</nav><script src=/app/native.js></script><main>revision "+revision.get()+"</main></html>";}
    void serve(HttpExchange exchange) throws IOException {
      Visit visit=visit(exchange);visits.add(visit);String path=visit.path();boolean alice=visit.account().equals("Alice");
      if(path.startsWith("/app/steps/")){
        try{Thread.sleep(80);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        StringBuilder body=new StringBuilder("<!doctype html><html><title>Native lifecycle</title>");
        if(path.equals("/app/steps/0"))for(int i=1;i<=40;i++)body.append("<a href=/app/steps/").append(i).append(">Step ").append(i).append("</a>");
        reply(exchange,200,"text/html",body.append("<p>").append(path).append("</p></html>").toString());return;
      }
      switch(path){
        case "/app/","/app"->reply(exchange,200,"text/html; charset=UTF-8",html());
        case "/app/native-menu"->reply(exchange,200,"text/html","<!doctype html><html><title>Native menu</title><a href=/app/api/me>Account menu</a><a href=/app/api-docs>API document</a></html>");
        case "/app/native-htmx"->reply(exchange,200,"text/html","<div>read-only fragment "+revision.get()+"</div>");
        case "/app/native.js"->reply(exchange,200,"application/javascript","fetch('/app/js-only');\nconst api_key='"+API_SECRET+"';\n");
        case "/app/js-only"->reply(exchange,200,"application/json","{\"api_key\":\""+API_SECRET+"\",\"database_url\":\"postgresql://app:"+DB_PASSWORD+"@db.internal/app\",\"diagnostic\":\"org.postgresql.util.PSQLException SQLSTATE[08006]\",\"revision\":"+revision.get()+"}");
        case "/app/api-docs"->reply(exchange,200,"application/json","{\"openapi\":\"3.0.0\",\"paths\":{\"/app/api/me\":{\"get\":{}},\"/app/native-form\":{\"post\":{}}}}");
        case "/app/api/me"->reply(exchange,alice?200:401,"application/json",alice?"{\"id\":\"alice\",\"role\":\"reader\"}":"{\"error\":\"login required\"}");
        case "/app/private"->reply(exchange,alice?200:403,"application/json",alice?"{\"owner\":\"alice\",\"revision\":"+revision.get()+"}":"{\"error\":\"forbidden\"}");
        case "/app/redirect"->{exchange.getResponseHeaders().set("Location","/outside/sensitive");reply(exchange,302,"text/plain","outside application redirect");}
        case "/app/native-post","/app/native-form","/outside/sensitive","/app/excluded/private"->reply(exchange,405,"text/plain","must not execute this sentinel");
        default->reply(exchange,200,"text/html","<html><title>Missing native fixture route</title><body>Not found in this application</body></html>");
      }
    }
    Predicate<String> allowed(String origin){return url->{String clean=ScopeInspectionRunner.normalized(url);return clean!=null&&(clean.equals(origin+"/app")||clean.startsWith(origin+"/app/"))&&!clean.startsWith(origin+"/app/excluded");};}
    List<ScopeInspection.Profile> profiles(String origin){return List.of(new ScopeInspection.Profile("anonymous","비로그인",Map.of(),null),new ScopeInspection.Profile("alice","Alice",Map.of("cookie",SESSION),new CookieSessionProbe.Config(origin+"/app/api/me","$.id","alice")));}
    ScopeInspection.Options options(String origin,String path,int budget,int delay){return new ScopeInspection.Options(origin+path,budget,2,delay,3000,1024*1024,3,false,true,profiles(origin),true,25,80);}
    void reset(){visits.clear();trapVisits.clear();}
    @Override public void close(){WorkbenchScope.release(api);http.stop(0);https.stop(0);trap.stop(0);workers.shutdownNow();}
  }

  /** Checks live child argv and only Katana-owned temporary cwd files, never evidence stores. */
  static final class ChildMonitor implements AutoCloseable {
    final Set<Long> processes=ConcurrentHashMap.newKeySet();final Set<Path> directories=ConcurrentHashMap.newKeySet();
    final AtomicInteger filesChecked=new AtomicInteger();final AtomicReference<AssertionError> problem=new AtomicReference<>();
    final Path binary;volatile boolean active=true;final Thread thread;
    ChildMonitor(Path binary){this.binary=binary;thread=new Thread(this::watch,"native-katana-credential-monitor");thread.setDaemon(true);thread.start();}
    void watch(){while(active){try{scan();Thread.sleep(10);}catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}catch(AssertionError failure){problem.compareAndSet(null,failure);}catch(Exception ignored){}}}
    void scan() throws Exception {
      for(ProcessHandle child:ProcessHandle.current().descendants().toList()){
        String command=child.info().command().orElse("");if(!command.equals(binary.toString())&&!command.endsWith("/katana"))continue;
        processes.add(child.pid());String[] argv=child.info().arguments().orElse(new String[0]);
        for(String arg:argv)if(!privateFree(arg))throw new AssertionError("Katana argv contains private fixture data");
        if(Arrays.asList(argv).contains("-H")||Arrays.asList(argv).contains("-headers"))throw new AssertionError("Katana must not own target account headers");
        try{
          Path cwd=Files.readSymbolicLink(Path.of("/proc",Long.toString(child.pid()),"cwd"));
          if(!cwd.getFileName().toString().startsWith("workbench-katana-"))continue;directories.add(cwd);
          try(var entries=Files.walk(cwd)){for(Path path:entries.filter(Files::isRegularFile).toList())if(Files.size(path)<=1024*1024){
            String content=Files.readString(path,StandardCharsets.UTF_8);filesChecked.incrementAndGet();if(!privateFree(content))throw new AssertionError("Katana temporary files contain private fixture data");
          }}
        }catch(IOException ignored){}
      }
    }
    void verify(){if(problem.get()!=null)throw problem.get();check(!processes.isEmpty(),"an actual pinned Katana process was observed");check(!directories.isEmpty()&&filesChecked.get()>0,"target account values stay out of live Katana argv and owned temporary files");}
    @Override public void close(){active=false;thread.interrupt();try{thread.join(2000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
  }

  static final class Pipeline implements AutoCloseable {
    final Fixture fixture;final ScopeInspection.Options options;final String profile;final ScopeInspectionRunner runner;final ScopeKatana katana;final ChildMonitor monitor;
    final List<ScopeInspection.Candidate> candidates=new CopyOnWriteArrayList<>();final List<ScopeKatanaProxy.Blocked> blocked=new CopyOnWriteArrayList<>();
    final List<DiskTrafficStore.PacketRef> fetched=new CopyOnWriteArrayList<>();final AtomicInteger finishes=new AtomicInteger();final AtomicBoolean nativeComplete=new AtomicBoolean();
    Pipeline(Fixture fixture,Path binary,String origin,String path,int budget,int delay,String profile) throws Exception {
      this.fixture=fixture;this.profile=profile;var initial=fixture.options(origin,path,budget,delay);
      options=new ScopeInspection.Options(initial.url(),initial.budget(),initial.depth(),initial.delayMillis(),initial.timeoutMillis(),initial.maxBodyBytes(),initial.maxRedirects(),initial.browser(),initial.compareAccounts(),initial.profiles().stream().sorted(Comparator.comparingInt(p->p.id().equals(profile)?0:1)).toList(),true,initial.katanaSeconds(),initial.katanaPages());
      runner=new ScopeInspectionRunner(fixture.api,options,fixture.allowed(origin),snapshot->{});
      runner.registerKatana(profile,System.currentTimeMillis()+45_000);monitor=new ChildMonitor(binary);
      katana=new ScopeKatana(new ScopeKatana.Config(binary.toString(),25,80,3,3000,1024*1024,0),options.url(),List.of(origin+"/app/"),List.of(origin+"/app/excluded"),fixture.allowed(origin),(url,method)->{
        DiskTrafficStore.PacketRef ref=runner.fetchKatana(url,method).get(40,TimeUnit.SECONDS);fetched.add(ref);
        var response=ref.response();return new ScopeKatanaProxy.Response(response.statusCode(),response.headers().stream().map(h->new ScopeKatanaProxy.Header(h.name(),h.value())).toList(),response.body().getBytes());
      },candidate->{candidates.add(candidate);runner.offerKatana(candidate);},item->{blocked.add(item);runner.offerKatana(new ScopeInspection.Candidate(item.url(),item.method(),"Katana 차단",item.reason(),false));},(complete,note)->{
        nativeComplete.set(complete);finishes.incrementAndGet();runner.finishKatana(complete,note);
      },snapshot->{});
    }
    void start(){runner.start();katana.start();}
    void finish() throws Exception {await(()->nativeTerminal(katana.snapshot()),"native process terminal state");await(()->terminal(runner.snapshot()),"coordinator terminal state");monitor.verify();}
    Map<String,Object> report(String name){var result=runner.snapshot();var nativeResult=katana.snapshot();var accounts=new TreeMap<String,Integer>();for(var visit:fixture.visits)accounts.merge(visit.account(),1,Integer::sum);
      Map<String,Object> report=new LinkedHashMap<>();report.put("name",name);report.put("protocol",options.url().startsWith("https:")?"HTTPS_CONNECT":"HTTP");report.put("runnerState",result.state().name());report.put("nativeState",nativeResult.state());report.put("nativeComplete",nativeComplete.get());report.put("sent",result.sent());report.put("actualRequests",fixture.visits.size());report.put("accountRequests",accounts);report.put("proxyOriginals",fetched.size());report.put("nativeRecords",nativeResult.records());report.put("nativeEndpoints",nativeResult.endpoints());report.put("nativeExcluded",nativeResult.excluded());report.put("blockedReasons",blocked.stream().map(ScopeKatanaProxy.Blocked::reason).distinct().toList());report.put("blockedCount",blocked.size());report.put("candidateMethods",candidates.stream().collect(java.util.stream.Collectors.groupingBy(ScopeInspection.Candidate::method,TreeMap::new,java.util.stream.Collectors.counting())));report.put("pending",result.pending());report.put("partial",result.partial());report.put("findings",result.findings().stream().collect(java.util.stream.Collectors.groupingBy(ScopeInspection.Finding::category,TreeMap::new,java.util.stream.Collectors.counting())));report.put("observedNativeProcesses",monitor.processes.size());report.put("katanaTemporaryFilesChecked",monitor.filesChecked.get());return report;}
    Map<String,Object> diagnostics(){Map<String,Object> value=new LinkedHashMap<>();value.put("startUrl",ScopeInspectionAnalysis.safeUrl(options.url()));value.put("runnerState",runner.snapshot().state().name());value.put("nativeState",katana.snapshot().state());
      value.put("candidates",candidates.stream().limit(250).map(c->Map.of("url",ScopeInspectionAnalysis.safeUrl(c.url()),"method",c.method(),"source",safeText(c.source()),"label",safeText(c.label()),"executable",c.executable())).toList());
      value.put("proxyOriginals",fetched.stream().limit(250).map(ref->Map.of("id",ref.id(),"url",ScopeInspectionAnalysis.safeUrl(ref.url()),"method",ref.method())).toList());
      value.put("visits",fixture.visits.stream().limit(500).map(v->Map.of("method",v.method(),"path",safeText(v.path()),"account",v.account(),"tls",v.tls())).toList());
      value.put("blocked",blocked.stream().limit(250).map(b->Map.of("url",ScopeInspectionAnalysis.safeUrl(b.url()),"method",b.method(),"reason",safeText(b.reason()))).toList());return value;}
    @Override public void close() throws Exception {
      DIAGNOSTICS.add(diagnostics());try{katana.close();runner.close();await(()->monitor.processes.stream().noneMatch(pid->ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))&&monitor.directories.stream().noneMatch(Files::exists),"native process and owned temporary workspace cleanup");
        check(true,"closing native collection terminates its observed children and removes owned temporary files");
      }finally{monitor.close();}
    }
  }

  static void comprehensive(Pipeline pipeline,boolean tls) throws Exception {
    var result=pipeline.runner.snapshot();var fixture=pipeline.fixture;String origin=tls?fixture.httpsOrigin:fixture.httpOrigin;
    check(result.state()==ScopeInspection.State.COMPLETED,"native producer and coordinator finish their bounded queue");
    check(Set.of("COMPLETED","PARTIAL").contains(pipeline.katana.snapshot().state()),"real native process exits without a launch or parse failure");
    check(pipeline.finishes.get()==1&&pipeline.katana.snapshot().records()>0&&!pipeline.fetched.isEmpty(),"native success is backed by JSONL records and actual proxied responses");
    check(result.sent()==fixture.visits.size()&&result.sent()<=pipeline.options.budget(),"all Katana, identity, reference and application requests share the exact displayed HTTP budget");
    check(fixture.visits.stream().allMatch(v->Set.of("GET","HEAD").contains(v.method()))&&fixture.visits.stream().noneMatch(v->v.account().equals("unexpected-credential")),"target receives only read methods and selected Runner account snapshots");
    check(fixture.trapVisits.isEmpty()&&fixture.visits.stream().noneMatch(v->v.path().startsWith("/outside")||v.path().startsWith("/app/excluded")),"path exclusions, another Origin port and redirect escape never reach a target");
    check(fixture.visits.stream().noneMatch(v->Set.of("/app/native-post","/app/native-form").contains(v.path())),"discovered htmx and HTML write actions never execute at the target");
    check(pipeline.candidates.stream().anyMatch(c->c.source().toLowerCase(Locale.ROOT).contains("katana")&&c.url().equals(origin+"/app/native-menu")),"actual Katana discovers an unquoted menu absent from the original analyzer");
    check(pipeline.candidates.stream().anyMatch(c->c.source().toLowerCase(Locale.ROOT).contains("katana")&&c.url().equals(origin+"/app/js-only")),"actual Katana JS crawling discovers the JS-only endpoint");
    check(pipeline.candidates.stream().anyMatch(c->c.method().equals("POST")&&!c.executable()&&(c.url().endsWith("native-post")||c.url().endsWith("native-form"))),"native form or htmx POST metadata remains explicitly non-executable");
    check(pipeline.candidates.stream().anyMatch(c->c.source().equals("Katana 폼")&&!c.executable()&&c.url().endsWith("native-form")),"real native form extraction keeps declarations without submitting them");
    check(pipeline.candidates.stream().anyMatch(c->c.source().equals("Katana 환경")&&!c.executable()),"real native technology detection contributes separate metadata evidence");
    Set<String> nativeJsIds=pipeline.fetched.stream().filter(ref->ref.url().equals(origin+"/app/js-only")).map(DiskTrafficStore.PacketRef::id).collect(java.util.stream.Collectors.toSet());
    check(!nativeJsIds.isEmpty()&&result.pages().stream().anyMatch(page->page.original()!=null&&nativeJsIds.contains(page.original().id())
        &&(page.note().toLowerCase(Locale.ROOT).contains("katana")||result.findings().stream().anyMatch(f->f.category().equals("KATANA")&&f.originalId().equals(page.original().id())&&f.url().equals(page.url())))),
        "native JS fetch links the exact retained HTTP or cache original through explicit provenance");
    for(var ref:pipeline.fetched){check(pipeline.runner.original(ref.id())==ref,"proxied response ID resolves to its exact retained original");boolean cookie=ref.request().headers().stream().anyMatch(h->h.name().equalsIgnoreCase("Cookie")&&h.value().equals(SESSION));check(cookie==pipeline.profile.equals("alice"),"Katana proxy adds only the declared profile through the Runner");}
    check(result.findings().stream().anyMatch(f->f.category().equals("ENVIRONMENT"))&&result.findings().stream().anyMatch(f->f.category().equals("DATABASE")),"actual native responses feed server, WAS and DB evidence analysis");
    check(result.findings().stream().anyMatch(f->f.category().equals("API"))&&result.findings().stream().anyMatch(f->f.category().equals("SECRET"))&&result.findings().stream().anyMatch(f->f.category().equals("ACCESS")),"native collection preserves API, masked secret and account-difference findings");
    var secretPage=result.pages().stream().filter(p->p.url().endsWith("/app/js-only")&&p.original()!=null).findFirst().orElseThrow();
    check(secretPage.original().response().bodyToString().contains(API_SECRET)&&privateFree(result.toString())&&privateFree(pipeline.katana.snapshot().toString())&&privateFree(pipeline.candidates.toString()),"raw evidence retains private bytes while result, candidate and native status views remain masked");
    check(result.findings().stream().anyMatch(f->f.category().equals("COVERAGE")),"excluded routes and write metadata retain meaningful coverage explanations");
    check(fixture.visits.stream().anyMatch(v->v.tls()==tls),tls?"real native HTTPS uses CONNECT and fixture-only target TLS trust":"real native HTTP passes through the production authenticated proxy");
  }

  static Map<String,Object> run(Path binary,Path output) throws Exception {
    check(Files.isRegularFile(binary)&&Files.isExecutable(binary),"explicit native executable exists outside the application artifact");check(sha(binary).equals(BINARY_SHA256),"native executable matches the pinned official 1.8.0 extraction SHA-256");
    SSLContext defaultTls=SSLContext.getDefault();String trustProperty=System.getProperty("javax.net.ssl.trustStore");
    List<Map<String,Object>> cases=new ArrayList<>();Path historyDirectory=Files.createTempDirectory("native-katana-history-");
    try(Fixture fixture=new Fixture()){
      var staticAnalysis=ScopeInspectionAnalysis.analyze(fixture.httpOrigin+"/app/",200,"Content-Type: text/html",fixture.html(),"비로그인","");
      check(staticAnalysis.candidates().stream().noneMatch(c->c.url().endsWith("/app/native-menu")||c.url().endsWith("/app/native.js")||c.url().endsWith("/app/js-only")),"native-only menu and script input are not supplied by the original static analyzer");
      AtomicReference<String> historyScope=new AtomicReference<>(fixture.httpOrigin+"/app/!excluded");var history=new ScopeInspectionHistory(historyDirectory,"66666666-6666-4666-8666-666666666666",historyScope::get);
      ScopeInspection.Options firstOptions;ScopeInspection.Snapshot firstSnapshot;
      try(Pipeline pipeline=new Pipeline(fixture,binary,fixture.httpOrigin,"/app/",300,0,"alice")){
        check(fixture.visits.isEmpty(),"preparing native collection sends zero target requests");pipeline.start();pipeline.finish();comprehensive(pipeline,false);firstOptions=pipeline.options;firstSnapshot=pipeline.runner.snapshot();history.save(firstOptions,firstSnapshot);cases.add(pipeline.report("http-native-multi-account"));
      }
      fixture.reset();fixture.revision.incrementAndGet();
      try(Pipeline pipeline=new Pipeline(fixture,binary,fixture.httpOrigin,"/app/",300,0,"alice")){
        pipeline.start();pipeline.finish();var result=pipeline.runner.snapshot();var changes=history.changes(pipeline.options,result);
        check(changes.stream().anyMatch(f->f.title().contains("응답 내용 변경")),"repeating actual native collection detects changed response content in masked history");
        historyScope.set("another-scope");check(history.changes(pipeline.options,result).isEmpty(),"native history comparison stays partitioned by the captured Scope condition");historyScope.set(fixture.httpOrigin+"/app/!excluded");history.save(pipeline.options,result);
        try(var files=Files.walk(historyDirectory)){for(Path path:files.filter(Files::isRegularFile).toList())check(privateFree(Files.readString(path)),"persisted native history contains metadata without credentials or private body values");}
        cases.add(pipeline.report("http-native-history-change"));
      }
      fixture.reset();
      try(Pipeline pipeline=new Pipeline(fixture,binary,fixture.httpsOrigin,"/app/",300,0,"anonymous")){
        pipeline.start();pipeline.finish();comprehensive(pipeline,true);cases.add(pipeline.report("https-connect-native-multi-account"));
      }
      fixture.reset();
      try(Pipeline pipeline=new Pipeline(fixture,binary,fixture.httpOrigin,"/app/steps/0",300,20,"anonymous")){
        pipeline.start();await(()->pipeline.katana.snapshot().records()>0&&!nativeTerminal(pipeline.katana.snapshot()),"native lifecycle crawl is active");pipeline.runner.pause();pipeline.katana.pause();
        await(()->pipeline.runner.snapshot().state()==ScopeInspection.State.PAUSED&&pipeline.katana.snapshot().state().equals("PAUSED"),"both collection stages pause");Thread.sleep(200);int paused=pipeline.runner.snapshot().sent();int requests=fixture.visits.size();Thread.sleep(200);
        check(pipeline.runner.snapshot().sent()==paused&&fixture.visits.size()==requests,"pausing real native collection dispatches no additional target requests");pipeline.runner.resume();pipeline.katana.resume();await(()->pipeline.runner.snapshot().sent()>paused,"native collection resumes through the same worker");
        pipeline.runner.stop();pipeline.katana.stop();await(()->pipeline.runner.snapshot().state()==ScopeInspection.State.STOPPED,"native coordinated stop");Thread.sleep(200);int stopped=fixture.visits.size();Thread.sleep(150);
        check(fixture.visits.size()==stopped,"stopping the actual native process prevents further target dispatch");check(pipeline.runner.snapshot().pending()>0,"stop retains uninspected native work and partial coverage");pipeline.monitor.verify();cases.add(pipeline.report("native-pause-resume-stop"));
      }
      fixture.reset();
      try(Pipeline pipeline=new Pipeline(fixture,binary,fixture.httpOrigin,"/app/",4,0,"alice")){
        pipeline.start();pipeline.finish();var result=pipeline.runner.snapshot();check(result.sent()==4&&fixture.visits.size()==4&&result.state()==ScopeInspection.State.STOPPED,"a four-request global budget includes native identity and baseline requests without overrun");
        check(result.pending()>0&&result.findings().stream().anyMatch(f->f.category().equals("COVERAGE")),"native budget exhaustion leaves explicit pending coverage rather than a complete assessment");cases.add(pipeline.report("native-shared-budget"));
      }
    }finally{try(var files=Files.walk(historyDirectory)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    check(SSLContext.getDefault()==defaultTls&&Objects.equals(System.getProperty("javax.net.ssl.trustStore"),trustProperty),"native HTTPS verification installs no system or user CA and changes no global TLS trust");
    Map<String,Object> report=new LinkedHashMap<>();report.put("result","passed");report.put("version",BuildInfo.VERSION);report.put("katanaVersion","1.8.0");report.put("binarySha256",BINARY_SHA256);report.put("officialArchiveSha256",ARCHIVE_SHA256);report.put("checks",checks);report.put("cases",cases);report.put("scope","Actual pinned Katana standard process, production loopback HTTP/HTTPS CONNECT proxy and single-worker Runner with isolated real targets. No native Burp GUI, browser DOM automation or external target assessment.");report.put("tlsTrust","Fixture certificate in an in-memory client KeyStore only; no user/system CA installation.");
    Files.createDirectories(output.getParent());Files.writeString(output,MetadataExport.json(report)+"\n",StandardCharsets.UTF_8);return report;
  }

  public static void main(String[] args) throws Exception {
    if(args.length!=2)throw new IllegalArgumentException("Usage: ScopeKatanaNativeHarness /absolute/katana /absolute/report.json");
    Path binary=Path.of(args[0]).toRealPath(),output=Path.of(args[1]).toAbsolutePath();
    try{run(binary,output);System.out.println("ScopeKatanaNativeHarness: "+checks+" checks passed (actual pinned Katana, loopback HTTP/HTTPS CONNECT; not native Burp GUI)");}
    catch(Exception|AssertionError failure){Files.createDirectories(output.getParent());Files.writeString(output,MetadataExport.json(Map.of("result","failed","version",BuildInfo.VERSION,"checks",checks,"failureType",failure.getClass().getSimpleName(),"diagnostics",DIAGNOSTICS,"scope","Actual Katana native harness; failure details are in the sanitized execution log."))+"\n",StandardCharsets.UTF_8);throw failure;}
  }
}
