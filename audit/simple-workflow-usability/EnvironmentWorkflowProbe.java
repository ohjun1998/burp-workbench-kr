package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.message.HttpRequestResponse;
import com.sun.net.httpserver.HttpServer;
import java.awt.Component;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import javax.swing.event.*;

/** Independent loopback HTTP fixture + production EnvironmentPanel. No native Burp or OS clicks. */
public final class EnvironmentWorkflowProbe extends WorkbenchTest {
  static final AtomicInteger targetCalls=new AtomicInteger(),fixtureCalls=new AtomicInteger(),opens=new AtomicInteger();
  static final List<String> actions=new ArrayList<>();
  static final com.sun.management.ThreadMXBean MX=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
  static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  static Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put(values[i].toString(),values[i+1]);return out;}
  static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
  static String sha(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
  record Received(HttpRequest request,HttpResponse response){}
  static Received fetch(String url)throws Exception{
    var received=CLIENT.send(java.net.http.HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());
    List<HttpHeader> headers=new ArrayList<>();received.headers().map().forEach((name,values)->values.forEach(v->headers.add(header(name,v))));
    return new Received(ScopeInspectionFixture.wireRequest(url,"GET",List.of(),new byte[0]),ScopeInspectionFixture.wireResponse(received.statusCode(),headers,received.body()));
  }
  static HttpServer server()throws Exception{
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{
      fixtureCalls.incrementAndGet();boolean selected=exchange.getRequestURI().getPath().equals("/selected");
      String body=selected?"{\"items\":["+String.join(",",Collections.nCopies(180,"{\"name\":\"public-fixture-record\",\"value\":123}"))+"]}":"<html><a href='/selected'>Review fixture</a></html>";
      byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",selected?"application/json":"text/html");exchange.getResponseHeaders().set("Server","nginx");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });server.start();return server;
  }
  static String origin(HttpServer server){return "http://127.0.0.1:"+server.getAddress().getPort();}
  static final class Events implements DocumentListener{
    int count;public void insertUpdate(DocumentEvent e){count++;}public void removeUpdate(DocumentEvent e){count++;}public void changedUpdate(DocumentEvent e){count++;}
  }
  static TargetWindow window(EnvironmentPanel panel,String field)throws Exception{return (TargetWindow)get(panel,field);}
  static void select(EnvironmentPanel panel,String url){for(int i=0;i<panel.candidates.size();i++)if(panel.candidates.get(i).url().equals(url)){int view=panel.pathTable.convertRowIndexToView(i);panel.pathTable.setRowSelectionInterval(view,view);return;}throw new AssertionError("Real selected URL not present");}
  static Map<String,Object> measure(EnvironmentPanel panel,EnvironmentPanel.Observation observation,String url,int index,String kind)throws Exception{
    AtomicReference<Map<String,Object>> answer=new AtomicReference<>();
    edt(()->{
      select(panel,url);panel.showSelected();panel.requestView.select(4,14);panel.responseView.select(90,125);
      int requestStart=panel.requestView.getSelectionStart(),requestEnd=panel.requestView.getSelectionEnd(),responseStart=panel.responseView.getSelectionStart(),responseEnd=panel.responseView.getSelectionEnd();
      var packet=panel.currentPacket(url);Events requestEvents=new Events(),responseEvents=new Events();panel.requestView.getDocument().addDocumentListener(requestEvents);panel.responseView.getDocument().addDocumentListener(responseEvents);
      AtomicInteger pathEvents=new AtomicInteger(),techEvents=new AtomicInteger();TableModelListener path=e->pathEvents.incrementAndGet(),tech=e->techEvents.incrementAndGet();panel.paths.addTableModelListener(path);panel.tech.addTableModelListener(tech);
      long allocated=MX.getThreadAllocatedBytes(Thread.currentThread().getId()),started=System.nanoTime();int httpBefore=targetCalls.get();
      for(int i=0;i<20;i++)panel.applyObservation(observation);
      long nanos=System.nanoTime()-started;allocated=MX.getThreadAllocatedBytes(Thread.currentThread().getId())-allocated;
      boolean reading=panel.requestView.getSelectionStart()==requestStart&&panel.requestView.getSelectionEnd()==requestEnd&&panel.responseView.getSelectionStart()==responseStart&&panel.responseView.getSelectionEnd()==responseEnd;
      require(url.equals(panel.selectedUrl())&&panel.currentPacket(url)==packet,"exact original and account ownership survive metadata observations");require(targetCalls.get()==httpBefore,"metadata review emits no automatic HTTP");
      answer.set(map("sample",index,"kind",kind,"updates",20,"edtMillis",nanos/1e6,"edtAllocatedBytes",allocated,"pathModelEvents",pathEvents.get(),"techModelEvents",techEvents.get(),"requestDocumentEvents",requestEvents.count,"responseDocumentEvents",responseEvents.count,"readingSelectionPreserved",reading,"exactOriginalPreserved",true,"automaticHttp",targetCalls.get()-httpBefore));
      panel.requestView.getDocument().removeDocumentListener(requestEvents);panel.responseView.getDocument().removeDocumentListener(responseEvents);panel.paths.removeTableModelListener(path);panel.tech.removeTableModelListener(tech);
    });return answer.get();
  }
  static Map<String,Object> summarize(List<Map<String,Object>> values){return map("samples",values,"medianEdtMillis",values.stream().mapToDouble(s->((Number)s.get("edtMillis")).doubleValue()).sorted().skip(2).findFirst().orElseThrow(),"maxEdtMillis",values.stream().mapToDouble(s->((Number)s.get("edtMillis")).doubleValue()).max().orElseThrow(),"medianEdtAllocatedBytes",values.stream().mapToLong(s->((Number)s.get("edtAllocatedBytes")).longValue()).sorted().skip(2).findFirst().orElseThrow());}
  public static void main(String[] args)throws Exception{
    if(args.length<1||args.length>2)throw new IllegalArgumentException("Output directory and optional phase required");
    Path output=Path.of(args[0]);Files.createDirectories(output);ScopeInspectionFixture.installFactory();MX.setThreadAllocatedMemoryEnabled(true);
    HttpServer first=server(),second=server();EnvironmentPanel panel=null;MontoyaApi api=null;
    try{
      String base=origin(first),other=origin(second),selectedUrl=base+"/selected";
      Received observed=fetch(base+"/unrelated"),otherObserved=fetch(other+"/unrelated");
      Http http=proxy(Http.class,(p,m,a)->{if(!m.getName().equals("sendRequest"))return def(m);require(!SwingUtilities.isEventDispatchThread(),"explicit target HTTP runs off EDT");HttpRequest q=(HttpRequest)a[0];require(q.url().equals(selectedUrl),"only the selected fixture GET is sent");targetCalls.incrementAndGet();Received r=fetch(q.url());return proxy(HttpRequestResponse.class,(pp,mm,aa)->switch(mm.getName()){case "request"->r.request();case "response"->r.response();default->def(mm);});});
      api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http")?http:def(m));MontoyaApi activeApi=api;
      AtomicReference<EnvironmentPanel> reference=new AtomicReference<>();edt(()->reference.set(new EnvironmentPanel(activeApi)));panel=reference.get();EnvironmentPanel active=panel;
      var observation=panel.prepareObservation(observed.request(),observed.response());var otherObservation=panel.prepareObservation(otherObserved.request(),otherObserved.response());require(observation!=null&&otherObservation!=null,"actual HTTP responses prepare environment metadata");
      AtomicReference<Map<String,Object>> workflow=new AtomicReference<>();
      edt(()->{
        active.applyObservation(observation);active.applyObservation(otherObservation);active.origins.setSelectedItem(other);active.account.setSelectedIndex(1);
        active.compareMissing.setSelected(false);active.follow.setSelected(false);active.delay.setValue(0);
        boolean targetOnMain=SwingUtilities.isDescendingFrom(active.origins,active),accountOnMain=SwingUtilities.isDescendingFrom(active.account,active);
        try{
          for(String name:List.of("targetSettings","candidateSettings"))window(active,name).opener=(o,t,c,m,x)->{opens.incrementAndGet();return null;};
          if(!targetOnMain){actions.add("open target settings");window(active,"targetSettings").open();}
          actions.add("choose observed target");active.origins.setSelectedItem(base);
          if(!accountOnMain){actions.add("open candidate settings");window(active,"candidateSettings").open();}
          actions.add("choose anonymous account");active.account.setSelectedIndex(0);
          actions.add("select actual candidate path");select(active,selectedUrl);
          int before=targetCalls.get();require(before==0,"target/account selection emits no automatic HTTP");
          actions.add("explicitly start selected GET");active.single.doClick(0);
          workflow.set(map("requiredActions",actions.size(),"actions",List.copyOf(actions),"openedSettings",opens.get(),"targetVisibleOnMain",targetOnMain,"accountVisibleOnMain",accountOnMain,"optionalDialogCloseActionsExcluded",true));
        }catch(Exception failure){throw new RuntimeException(failure);}
      });
      long timeout=System.nanoTime()+Duration.ofSeconds(12).toNanos();while(System.nanoTime()<timeout){edt(()->{});if(panel.job==null)break;Thread.sleep(10);}require(panel.job==null&&panel.currentPacket(selectedUrl)!=null&&targetCalls.get()==1,"one explicit selected GET produces the actual fixture response");
      List<Map<String,Object>> same=new ArrayList<>(),different=new ArrayList<>();measure(panel,observation,selectedUrl,-1,"sameOrigin");measure(panel,otherObservation,selectedUrl,-1,"otherOrigin");for(int i=0;i<5;i++){same.add(measure(panel,observation,selectedUrl,i,"sameOrigin"));different.add(measure(panel,otherObservation,selectedUrl,i,"otherOrigin"));}
      var packet=panel.currentPacket(selectedUrl);Map<String,Object> report=map("probe","EnvironmentWorkflowProbe","productionClassLoadedFrom",EnvironmentPanel.class.getProtectionDomain().getCodeSource().getLocation().toString(),"nativeBurp",false,"osClicks",false,"browserDOM",false,"execution","production Swing headless with real isolated loopback HTTP responses through a Montoya adapter","workflow",workflow.get(),"sameOrigin",summarize(same),"otherOrigin",summarize(different),"warmupSamplesPerKind",1,"measuredSamplesPerKind",5,"preparedMetadataReplayedPerSample",20,"explicitTargetHttp",targetCalls.get(),"fixtureHttpRequests",fixtureCalls.get(),"responseSha256",sha(packet.response().getBytes(StandardCharsets.UTF_8)),"responseCharacters",packet.response().length(),"retainedHeapOrRSSMeasured",false);
      try(var jar=new java.util.jar.JarFile(Path.of(EnvironmentPanel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toFile())){report.put("selectedJarVersion",jar.getManifest().getMainAttributes().getValue("Implementation-Version"));}
      report.put("phase",args.length>1?args[1]:"unspecified");
      Files.writeString(output.resolve("result.json"),MetadataExport.json(report)+"\n");System.out.println("Environment workflow probe recorded to "+output.resolve("result.json"));
    }finally{if(panel!=null){EnvironmentPanel active=panel;edt(active::close);}first.stop(0);second.stop(0);if(api!=null)WorkbenchScope.release(api);}
  }
}
