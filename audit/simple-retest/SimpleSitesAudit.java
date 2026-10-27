package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real local applications through the released inspection classes; no simulated response server. */
public final class SimpleSitesAudit extends WorkbenchTest {
  static String string(Map<?,?> map,String key,String fallback){return Objects.toString(map.get(key),fallback);}
  static int number(Map<?,?> map,String key,int fallback){return map.get(key) instanceof Number value?value.intValue():fallback;}
  static boolean flag(Map<?,?> map,String key,boolean fallback){return map.get(key) instanceof Boolean value?value:fallback;}
  static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
  static Map<String,Object> object(Object... pairs){var value=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)value.put(pairs[i].toString(),pairs[i+1]);return value;}
  static void save(Path path,Object value)throws Exception{Files.createDirectories(path.getParent());Files.writeString(path,MetadataExport.json(value)+"\n",StandardCharsets.UTF_8);}
  static boolean terminal(ScopeInspection.State state){return Set.of(ScopeInspection.State.COMPLETED,ScopeInspection.State.STOPPED,ScopeInspection.State.FAILED,ScopeInspection.State.CLOSED).contains(state);}
  static String relative(Path output,Path file){return output.relativize(file).toString().replace('\\','/');}
  static Map<String,Object> finding(ScopeInspection.Finding value){return object("category",value.category(),"title",value.title(),"url",value.url(),"method",value.method(),"account",value.account(),"confidence",value.confidence(),"severity",value.severity(),"state",value.state(),"detail",value.detail(),"originalId",value.originalId(),"line",value.line(),"offset",value.offset(),"fingerprint",value.fingerprint());}
  static Map<String,Object> nativeSnapshot(ScopeKatana.Snapshot value){return object("state",value.state(),"message",value.message(),"records",value.records(),"endpoints",value.endpoints(),"excluded",value.excluded(),"startedAt",value.startedAt(),"finishedAt",value.finishedAt());}
  static String decode(burp.api.montoya.http.message.responses.HttpResponse response)throws Exception{return JsAnalysisPanel.readBody(response);}

  public static void main(String[] args)throws Exception{
    if(args.length!=3)throw new IllegalArgumentException("Usage: SimpleSitesAudit config.json output-directory katana-executable");
    Map<?,?> config=(Map<?,?>)MinerJson.parse(Files.readString(Path.of(args[0])));Path output=Path.of(args[1]).toAbsolutePath();Files.createDirectories(output);
    String base=string(config,"baseURL","").replaceAll("/$",""),start=string(config,"startURL",base+"/"),scopePrefix=string(config,"scopeURL",base+"/");
    URI origin=URI.create(base);if(!Set.of("127.0.0.1","localhost","::1").contains(origin.getHost()))throw new IllegalArgumentException("Actual app retest permits local loopback targets only");
    List<ScopeInspection.Profile> profiles=new ArrayList<>();
    for(Object row:(List<?>)config.get("profiles")){Map<?,?> value=(Map<?,?>)row;var headers=new LinkedHashMap<String,String>();if(value.get("headers") instanceof Map<?,?> raw)for(var entry:raw.entrySet())headers.put(entry.getKey().toString(),entry.getValue().toString());
      CookieSessionProbe.Config identity=null;if(value.get("identity") instanceof Map<?,?> probe)identity=new CookieSessionProbe.Config(string(probe,"url",base+"/"),string(probe,"path","$.id"),string(probe,"expected",""));
      profiles.add(new ScopeInspection.Profile(string(value,"id","anonymous"),string(value,"label","비로그인"),headers,identity));
    }
    String katanaProfile=string(config,"katanaProfile",profiles.get(profiles.size()-1).id());
    profiles.sort(Comparator.comparingInt(profile->profile.id().equals(katanaProfile)?0:1));
    ScopeInspectionFixture.installFactory();AtomicInteger calls=new AtomicInteger();List<Map<String,Object>> transport=new CopyOnWriteArrayList<>();
    var client=java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).followRedirects(java.net.http.HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    Http http=proxy(Http.class,(p,m,arguments)->{
      if(!m.getName().equals("sendRequest"))return def(m);HttpRequest request=(HttpRequest)arguments[0];
      if(!EnvironmentCatalog.origin(URI.create(request.url())).equals(EnvironmentCatalog.origin(origin)))throw new AssertionError("Transport Origin boundary");
      if(!Set.of("GET","HEAD").contains(request.method()))throw new AssertionError("Inspection must use read methods");
      int index=calls.incrementAndGet();String name=String.format(Locale.ROOT,"%04d",index);Path directory=output.resolve("transport");Files.createDirectories(directory);
      Path requestFile=directory.resolve(name+".request.http");Files.write(requestFile,request.toByteArray().getBytes());String account="비로그인";
      for(var profile:profiles)if(!profile.headers().isEmpty()&&profile.headers().entrySet().stream().allMatch(entry->Objects.equals(request.headerValue(entry.getKey()),entry.getValue())))account=profile.label();
      Map<String,Object> record=object("index",index,"url",request.url(),"method",request.method(),"account",account,"requestFile",relative(output,requestFile));
      try{
        var builder=java.net.http.HttpRequest.newBuilder(URI.create(request.url())).timeout(Duration.ofMillis(number(config,"timeoutMillis",5000))).method(request.method(),java.net.http.HttpRequest.BodyPublishers.noBody());
        for(HttpHeader header:request.headers())if(!Set.of("host","connection","content-length","upgrade","http2-settings").contains(header.name().toLowerCase(Locale.ROOT)))builder.header(header.name(),header.value());
        var received=client.send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());var headers=new ArrayList<HttpHeader>();received.headers().map().forEach((key,values)->values.forEach(value->headers.add(header(key,value))));
        var response=ScopeInspectionFixture.wireResponse(received.statusCode(),headers,received.body());registerTrafficPacket(request,response);
        Path responseFile=directory.resolve(name+".response.http"),bodyFile=directory.resolve(name+".body.bin"),decodedFile=directory.resolve(name+".decoded.txt");Files.write(responseFile,response.toByteArray().getBytes());Files.write(bodyFile,received.body());
        String decodeError="";try{Files.writeString(decodedFile,decode(response),StandardCharsets.UTF_8);}catch(Exception failure){decodeError=failure.getClass().getSimpleName();Files.writeString(decodedFile,"",StandardCharsets.UTF_8);}
        record.putAll(object("status",received.statusCode(),"responseHeaders",headers.stream().map(h->object("name",h.name(),"value",h.value())).toList(),"responseFile",relative(output,responseFile),"bodyFile",relative(output,bodyFile),"decodedBodyFile",relative(output,decodedFile),"bodySHA256",hash(received.body()),"bodyBytes",received.body().length,"decodeError",decodeError));transport.add(record);
        return proxy(HttpRequestResponse.class,(pp,mm,aa)->switch(mm.getName()){case "request"->request;case "response"->response;default->def(mm);});
      }catch(Exception failure){record.putAll(object("status",0,"error",failure.getClass().getSimpleName(),"errorNote",Objects.toString(failure.getMessage(),"")));transport.add(record);throw failure;}
    });
    MontoyaApi fallback=SimpleWorkflowTest.previewApi();MontoyaApi api=proxy(MontoyaApi.class,(p,m,arguments)->m.getName().equals("http")?http:m.invoke(fallback,arguments));WorkbenchScope.install(api).add(scopePrefix,false);
    var allowed=(java.util.function.Predicate<String>)url->WorkbenchScope.contains(api,url)&&EnvironmentCatalog.origin(URI.create(url)).equals(EnvironmentCatalog.origin(origin));
    int budget=number(config,"budget",200),seconds=number(config,"katanaSeconds",30),pages=number(config,"katanaPages",50);
    var options=new ScopeInspection.Options(start,budget,number(config,"depth",2),number(config,"delayMillis",0),number(config,"timeoutMillis",5000),number(config,"maxBodyBytes",5*1024*1024),3,false,profiles.size()>1,profiles,true,seconds,pages);
    AtomicReference<ScopeInspection.Snapshot> latest=new AtomicReference<>();var runner=new ScopeInspectionRunner(api,options,allowed,latest::set);
    runner.registerKatana(katanaProfile,System.currentTimeMillis()+(seconds+30L)*1000);
    List<Map<String,Object>> nativeCandidates=new CopyOnWriteArrayList<>(),blocked=new CopyOnWriteArrayList<>();
    var katana=new ScopeKatana(new ScopeKatana.Config(args[2],seconds,pages,number(config,"depth",2),number(config,"timeoutMillis",5000),number(config,"maxBodyBytes",5*1024*1024),number(config,"delayMillis",0)),start,List.of(scopePrefix),List.of(),allowed,(url,method)->{
      CompletableFuture<DiskTrafficStore.PacketRef> future=runner.fetchKatana(url,method);try{var ref=future.get(seconds+15L,TimeUnit.SECONDS);var response=ref.response();return new ScopeKatanaProxy.Response(response.statusCode(),response.headers().stream().map(h->new ScopeKatanaProxy.Header(h.name(),h.value())).toList(),response.body().getBytes());}finally{if(!future.isDone())future.cancel(false);}
    },candidate->{nativeCandidates.add(object("url",candidate.url(),"method",candidate.method(),"source",candidate.source(),"label",candidate.label(),"executable",candidate.executable()));runner.offerKatana(candidate);},item->{blocked.add(object("url",item.url(),"method",item.method(),"reason",item.reason()));runner.offerKatana(new ScopeInspection.Candidate(item.url(),item.method(),"Katana 차단",item.reason(),false));},runner::finishKatana,snapshot->{});
    long started=System.currentTimeMillis();ScopeInspection.Snapshot snapshot;boolean wallLimited=false;int wallSeconds=number(config,"wallSeconds",seconds+75);
    try{
      runner.start();katana.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(wallSeconds);
      while(true){snapshot=runner.snapshot();if(terminal(snapshot.state())||snapshot.state()==ScopeInspection.State.PAUSED)break;if(System.nanoTime()>=deadline){wallLimited=true;runner.stop();katana.stop();snapshot=runner.snapshot();break;}Thread.sleep(25);}
      // Freeze the observed state before cleanup, so an authentic PAUSED result stays visible.
      ScopeInspection.Snapshot observed=snapshot;ScopeKatana.Snapshot observedKatana=katana.snapshot();runner.pause();katana.stop();
      var pageRows=new ArrayList<Map<String,Object>>();Set<String> written=new HashSet<>();Path originals=output.resolve("originals");Files.createDirectories(originals);
      for(var page:observed.pages()){
        var ref=page.original();String requestPath="",responsePath="",decodedPath="";
        if(ref!=null){String name=ref.id().replace(':','_');Path q=originals.resolve(name+".request.http"),s=originals.resolve(name+".response.http"),d=originals.resolve(name+".decoded.txt");
          if(written.add(ref.id())){Files.write(q,ref.request().toByteArray().getBytes());Files.write(s,ref.response().toByteArray().getBytes());try{Files.writeString(d,decode(ref.response()),StandardCharsets.UTF_8);}catch(Exception failure){Files.writeString(d,"",StandardCharsets.UTF_8);}}
          requestPath=relative(output,q);responsePath=relative(output,s);decodedPath=relative(output,d);
        }
        pageRows.add(object("originalId",ref==null?"":ref.id(),"url",page.url(),"method",page.method(),"account",page.account(),"status",page.status(),"classification",page.classification(),"complete",page.complete(),"note",page.note(),"digest",page.digest(),"profileRevision",page.profileRevision(),"requestFile",requestPath,"responseFile",responsePath,"decodedBodyFile",decodedPath));
      }
      Map<String,Object> productReport=object("available",false,"directory","product-export","error","");
      try{Class<?> exporter=Class.forName("kr.workbench.ScopeInspectionReport");var method=exporter.getDeclaredMethod("write",Path.class,ScopeInspection.Snapshot.class);method.setAccessible(true);method.invoke(null,output.resolve("product-export"),observed);productReport.put("available",true);}catch(ClassNotFoundException missing){productReport.put("error","Exporter absent from this released baseline JAR");}catch(Exception failure){productReport.put("error",failure.getClass().getSimpleName());}
      var report=object("version",BuildInfo.VERSION,"siteId",string(config,"siteId","site"),"siteName",string(config,"name","site"),"scenario",string(config,"scenario","root"),"baseURL",base,"startURL",start,"scopeURL",scopePrefix,"runId",observed.id(),"state",observed.state().name(),"message",observed.message(),"sent",observed.sent(),"actualTransportRequests",calls.get(),"completed",observed.completed(),"pending",observed.pending(),"excluded",observed.excluded(),"failed",observed.failed(),"partial",observed.partial(),"startedAt",started,"finishedAt",System.currentTimeMillis(),"budget",budget,"katana",nativeSnapshot(observedKatana),"findings",observed.findings().stream().map(SimpleSitesAudit::finding).toList(),"pages",pageRows,"candidates",observed.candidates().stream().map(c->object("url",c.url(),"method",c.method(),"source",c.source(),"label",c.label(),"executable",c.executable())).toList(),"nativeCandidates",nativeCandidates,"blocked",blocked,"transport",transport.stream().sorted(Comparator.comparingInt(row->((Number)row.get("index")).intValue())).toList(),"productReport",productReport,"scope","Released production ScopeInspectionRunner + actual pinned Katana through a wire-preserving Montoya-compatible HTTP adapter against original local applications. Not native Burp GUI or simulated target responses.");
      report.put("transportResponses",transport.stream().filter(row->((Number)row.get("status")).intValue()>0).count());report.put("transportErrors",transport.stream().filter(row->((Number)row.get("status")).intValue()==0).count());report.put("transportProtocol","HTTP/1.1; Java HTTP client received payload bytes and reconstructed normalized response headers, not raw TCP/chunk framing");
      report.put("wallLimitReached",wallLimited);report.put("auditStopReason",wallLimited?"Test harness stopped after "+wallSeconds+" wall-clock seconds; this is not a product automatic pause":"");
      report.put("parameters",object("budget",options.budget(),"depth",options.depth(),"delayMillis",options.delayMillis(),"timeoutMillis",options.timeoutMillis(),"maxBodyBytes",options.maxBodyBytes(),"katanaSeconds",seconds,"katanaPages",pages,"auditWallSeconds",wallSeconds,"katanaProfile",katanaProfile,"profileIds",profiles.stream().map(ScopeInspection.Profile::id).toList()));
      save(output.resolve("run.json"),report);System.out.println(MetadataExport.json(object("version",BuildInfo.VERSION,"siteId",report.get("siteId"),"scenario",report.get("scenario"),"state",report.get("state"),"sent",observed.sent(),"actualTransportRequests",calls.get(),"transportResponses",report.get("transportResponses"),"transportErrors",report.get("transportErrors"),"pages",pageRows.size(),"findings",observed.findings().size(),"partial",observed.partial(),"nativeRecords",katana.snapshot().records())));
    }finally{katana.close();runner.close();WorkbenchScope.release(api);}
  }
}
