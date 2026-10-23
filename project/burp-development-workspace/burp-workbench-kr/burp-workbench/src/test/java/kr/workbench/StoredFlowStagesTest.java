package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import javax.swing.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Stage transitions and actual original-source identity, including negative and stale-evidence cases. */
public final class StoredFlowStagesTest extends WorkbenchTest {
 static final String MARKER="WB_STAGE_TEST01";
 static StoredFlows.Flow last(StoredFlows f){var views=f.views();return views.get(views.size()-1);}
 static void write(StoredFlows f,String url,int status){f.observe(url,"POST","value="+MARKER,status,"application/json","{}");}
 static void read(StoredFlows f,String url,int status,String type,String body){f.observe(url,"GET","",status,type,body);}
 static HttpRequest withBody(String url,String method,String body){
  HttpRequest base=request(url,method,true,List.of(header("Authorization","Bearer SECRET")));
  HttpRequest created=proxy(HttpRequest.class,(p,m,a)->switch(m.getName()){
   case "body"->bytes(body.getBytes(StandardCharsets.UTF_8));case "bodyToString"->body;
   case "toString"->base.toString()+body;case "toByteArray"->bytes((base.toString()+body).getBytes(StandardCharsets.UTF_8));
   default->m.invoke(base,a);
  });registerTrafficPacket(created,null);return created;
 }
 static HttpResponse typed(int code,String type,String body){HttpResponse base=response(code,body);HttpResponse created=proxy(HttpResponse.class,(p,m,a)->m.getName().equals("toString")?"HTTP/1.1 "+code+"\r\nContent-Type: "+type+"\r\n\r\n"+body:m.invoke(base,a));registerTrafficPacket(null,created);return created;}
 static HttpRequestResponse pair(HttpRequest q,HttpResponse s){return proxy(HttpRequestResponse.class,(p,m,a)->switch(m.getName()){case "request"->q;case "response"->s;default->def(m);});}
 static void drain(ResponseReconPanel panel)throws Exception{CountDownLatch done=new CountDownLatch(1);panel.submit(done::countDown);if(!done.await(10,TimeUnit.SECONDS))throw new AssertionError("response observer queue did not finish");edt(()->{});}
 public static void main(String[] args)throws Exception{
  StoredFlows f=new StoredFlows();f.register(MARKER);
  read(f,"https://test/read",200,"text/html",MARKER);check(f.views().isEmpty(),"read alone cannot create stored-flow evidence");
  write(f,"https://test/store?token=SECRET#SECRET",200);
  var written=last(f);check(written.writeAccepted()&&!written.readbackObserved()&&!written.htmlObserved()&&written.browserEvidence().isEmpty(),"successful write is a distinct unconfirmed-storage stage");
  check(written.state().contains("저장 미확정"),"write success never claims persisted storage");
  read(f,"https://other/read",200,"text/html",MARKER);check(f.views().size()==1,"same marker cannot cross origin");
  read(f,"https://test/read",500,"text/html",MARKER);check(!last(f).readbackObserved()&&!last(f).htmlObserved(),"error HTML marker cannot advance storage/readback stages");
  read(f,"https://test/read",200,"application/json","{\"value\":\""+MARKER+"\"}");var json=last(f);
  check(json.readbackObserved()&&!json.htmlObserved()&&json.browserEvidence().isEmpty(),"JSON readback does not establish HTML output or execution");
  rejects(()->f.recordBrowserEvidence(json.id(),json.readSource().id(),"a".repeat(64)),"browser evidence requires an observed successful HTML read");
  read(f,"https://test/read",200,"text/html; charset=utf-8","<b>"+MARKER+"</b>");var html=last(f);
  check(html.readbackObserved()&&html.htmlObserved()&&!html.escapedCandidate()&&html.state().contains("실행 미검증"),"raw HTML advances output stage without execution claim");
  check(html.writeSource().id().equals(written.writeSource().id())&&!html.readSource().id().equals(html.writeSource().id()),"readback retains exact write and separate read source identities");
  rejects(()->f.recordBrowserEvidence(html.id(),html.readSource().id(),"not-a-hash"),"invalid browser artifact hash rejected");
  f.recordBrowserEvidence(html.id(),html.readSource().id(),"A".repeat(64));var confirmed=last(f);
  check(confirmed.browserEvidence().equals("a".repeat(64))&&!confirmed.browserRecordedAt().isEmpty()&&confirmed.state().contains("수동"),"explicit browser evidence stores digest and labels manual confirmation");
  String description=StoredFlows.describe(confirmed);check(!description.contains("SECRET")&&!description.contains("token=")&&description.contains("SF-P"),"portable summary contains source IDs and excludes query/fragment credentials");
  f.clearBrowserEvidence(confirmed.id());check(last(f).browserEvidence().isEmpty()&&last(f).state().contains("실행 미검증"),"manual execution claim can be explicitly withdrawn");
  f.recordBrowserEvidence(html.id(),html.readSource().id(),"a".repeat(64));
  read(f,"https://test/read",200,"text/html","&lt;script&gt;"+MARKER+"&lt;/script&gt;");var escaped=last(f);
  check(escaped.escapedCandidate()&&escaped.browserEvidence().isEmpty(),"new escaped observation invalidates previous browser evidence");
  check(escaped.state().contains("후보"),"escaped nearby text remains a candidate rather than a safety verdict");
  rejects(()->f.recordBrowserEvidence(html.id(),html.readSource().id(),"a".repeat(64)),"stale read-source selection cannot attach manual evidence to a new response");
  read(f,"https://test/xml",200,"application/xhtml+xml",MARKER);check(last(f).htmlObserved(),"XHTML is an explicit HTML output stage");
  read(f,"https://test/not-html",200,"text/not-html",MARKER);check(!last(f).htmlObserved(),"media type substring does not falsely establish HTML rendering");
  int count=f.views().size();f.observe("https://test/options","OPTIONS","",200,"text/html",MARKER);check(f.views().size()==count,"OPTIONS marker does not establish readback");
  write(f,"https://test/store",403);check(f.views().size()==1&&!last(f).writeAccepted(),"failed new write clears old successful association and browser claims");
  read(f,"https://test/read",200,"text/html",MARKER);check(f.views().size()==1,"old content after a failed new attempt cannot inherit successful storage stage");
  write(f,"https://test/store",201);read(f,"https://test/read",200,"text/html",MARKER);check(f.views().size()==2&&last(f).readbackObserved(),"fresh successful attempt can establish a new readback chain");
  rejects(()->f.register(null),"null marker rejected cleanly");
  check(f.observe("https://user:SECRET@test/a","POST",MARKER,200,"text/html",MARKER).isEmpty(),"credential-bearing URL is rejected before creating metadata");
  for(int i=0;i<600;i++)read(f,"https://test/read/"+i,200,"text/html",MARKER);check(f.views().size()==StoredFlows.MAX_FLOWS,"read observations stay bounded");
  StoredFlows bounded=new StoredFlows();bounded.register(MARKER);for(int i=0;i<150;i++)write(bounded,"https://h"+i+".test/store",200);
  check(bounded.views().size()==StoredFlows.MAX_WRITES,"cross-origin successful write associations have an independent cap");
  read(bounded,"https://h0.test/read",200,"text/html",MARKER);check(bounded.views().size()==StoredFlows.MAX_WRITES,"evicted write origin cannot establish a later chain");
  f.clear();check(f.views().isEmpty(),"clear removes registered marker associations and flow evidence");
  write(f,"https://test/store",200);check(f.views().isEmpty(),"cleared marker is not silently re-registered");

  ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->m.getName().equals("httpRequestResponse")?pair((HttpRequest)a[0],(HttpResponse)a[1]):def(m));
  var scope=proxy(burp.api.montoya.scope.Scope.class,(p,m,a)->m.getName().equals("isInScope")?a[0].toString().startsWith("https://test/"):def(m));
  MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("scope")?scope:def(m));AtomicReference<ResponseReconPanel> box=new AtomicReference<>();
  AtomicReference<List<HttpRequestResponse>> exported=new AtomicReference<>();AtomicReference<String> note=new AtomicReference<>();
  edt(()->{var panel=new ResponseReconPanel(api);box.set(panel);panel.flows.register(MARKER);panel.evidenceSink=(packets,text)->{exported.set(packets);note.set(text);};});
  var panel=box.get();HttpRequest qWrite=withBody("https://test/store?token=SECRET","POST",MARKER),qRead=withBody("https://test/read?token=SECRET","GET","");HttpResponse sWrite=typed(201,"application/json","{}"),sRead=typed(200,"text/html","<b>"+MARKER+"</b>");
  panel.offer(qWrite,sWrite);panel.offer(qRead,sRead);drain(panel);
  edt(()->{check(panel.flowModel.getColumnCount()==8&&panel.displayedFlows.size()==2,"actual response UI exposes separate write/read/HTML/browser stages");panel.flowTable.setRowSelectionInterval(1,1);panel.captureStored();check(exported.get().size()==2&&Arrays.equals(exported.get().get(0).request().toByteArray().getBytes(),qWrite.toByteArray().getBytes())&&Arrays.equals(exported.get().get(1).response().toByteArray().getBytes(),sRead.toByteArray().getBytes()),"stored evidence uses exact original ordered write/read packets");check(!note.get().contains("SECRET")&&note.get().contains("실행: 미검증"),"stored evidence note preserves uncertainty and excludes original credentials");check(panel.flowDetail.getText().contains("SF-P")&&!panel.flowDetail.getText().contains("SECRET"),"UI details expose safe source IDs without request values");});
  HttpRequest qError1=withBody("https://test/error-one","GET",""),qError2=withBody("https://test/error-two","GET","");var error=typed(500,"application/json","BadSqlGrammarException");
  panel.offer(qError1,error);panel.offer(qError2,error);drain(panel);
  edt(()->{int model=-1;for(int i=0;i<panel.findings.getRowCount();i++)if(panel.findings.getValueAt(i,1).toString().endsWith("/error-two"))model=i;check(model>=0,"grouped Passive finding displays current representative URL");panel.findingTable.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(1,SortOrder.DESCENDING)));int view=panel.findingTable.convertRowIndexToView(model);panel.findingTable.setRowSelectionInterval(view,view);panel.captureFinding();check(exported.get().size()==1&&Arrays.equals(exported.get().get(0).request().toByteArray().getBytes(),qError2.toByteArray().getBytes()),"sorted/grouped Passive finding sends matching current original packet");});
  WorkbenchScope local=WorkbenchScope.install(api);local.add("https://test/allowed-only",false);
  edt(()->{rejects(panel::captureFinding,"scope narrowing blocks Passive original export");rejects(panel::captureStored,"scope narrowing blocks whole stored-flow source pair");});WorkbenchScope.release(api);
  panel.offer(qError2,typed(500,"application/json","BadSqlGrammarException"+"x".repeat(EvidenceLinks.PACKET_LIMIT)));drain(panel);
  edt(()->{int model=-1;for(int i=0;i<panel.findings.getRowCount();i++)if(panel.findings.getValueAt(i,1).toString().endsWith("/error-two"))model=i;int view=panel.findingTable.convertRowIndexToView(model);panel.findingTable.setRowSelectionInterval(view,view);rejects(panel::captureFinding,"oversized latest representative cannot export a stale earlier packet");});
  var cache=new LinkedHashMap<String,HttpRequestResponse>();for(int i=0;i<150;i++)ResponseReconPanel.rememberPacket(cache,"P"+i,pair(qRead,sRead));check(cache.size()==100&&!cache.containsKey("P0"),"raw packet cache stays bounded independently of metadata");
  edt(()->{panel.close();check(panel.flowPackets.isEmpty()&&panel.findingPackets.isEmpty()&&panel.displayedFlowPackets.isEmpty()&&panel.displayedFindingPackets.isEmpty(),"unload releases cached and displayed source packets");});
  MontoyaObjectFactory previousFactory=ObjectFactoryLocator.FACTORY;
  ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->{throw new IllegalStateException("source retention unavailable");});
  check(EvidenceLinks.retain(qError1,error)==null,"optional source factory failure degrades to unavailable original");
  AtomicReference<ResponseReconPanel> unavailableBox=new AtomicReference<>();edt(()->unavailableBox.set(new ResponseReconPanel(api)));var unavailable=unavailableBox.get();unavailable.offer(qError1,error);drain(unavailable);
  edt(()->{check(unavailable.findings.getRowCount()>=1&&unavailable.findingPackets.isEmpty(),"source retention failure does not suppress independent Passive observation");unavailable.findingTable.setRowSelectionInterval(0,0);rejects(unavailable::captureFinding,"unavailable original cannot silently export a synthetic packet");unavailable.close();});
  ObjectFactoryLocator.FACTORY=previousFactory;
  AtomicReference<ResponseReconPanel> raceBox=new AtomicReference<>();edt(()->raceBox.set(new ResponseReconPanel(api)));var racePanel=raceBox.get();
  CountDownLatch retentionStarted=new CountDownLatch(1),allowRetention=new CountDownLatch(1);
  HttpRequest delayed=proxy(HttpRequest.class,(p,m,a)->{if(m.getName().equals("toByteArray")){retentionStarted.countDown();boolean released=false;while(!released){try{allowRetention.await();released=true;}catch(InterruptedException ignored){}}}return m.invoke(qError1,a);});
  Thread snapshotThread=new Thread(()->racePanel.offer(delayed,error),"manual-response-snapshot-fixture");snapshotThread.start();check(retentionStarted.await(10,TimeUnit.SECONDS),"unload race reaches bounded manual input snapshot");
  edt(racePanel::close);allowRetention.countDown();snapshotThread.join(10000);check(!snapshotThread.isAlive()&&racePanel.analysisBudget.metrics().queuedBytes()==0,"closing during manual snapshot releases its reservation without queueing work");check(racePanel.worker.awaitTermination(10,TimeUnit.SECONDS),"interrupted observer completes after blocked packet read is released");edt(()->{});
  check(racePanel.flowPackets.isEmpty()&&racePanel.findingPackets.isEmpty()&&racePanel.displayedFindingPackets.isEmpty(),"in-flight observation cannot repopulate sensitive packet caches after unload");
  System.out.println("StoredFlowStagesTest: "+checks+" checks passed");
 }
}
