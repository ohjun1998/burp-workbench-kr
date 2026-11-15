package kr.workbench;
import burp.api.montoya.*;import burp.api.montoya.http.*;import burp.api.montoya.http.message.*;import burp.api.montoya.http.message.requests.*;import burp.api.montoya.internal.*;import burp.api.montoya.persistence.*;
import javax.swing.*;import java.util.*;import java.util.List;import java.util.concurrent.atomic.*;import java.nio.file.*;import java.awt.image.BufferedImage;import javax.imageio.ImageIO;

/** Production panel + request mutation with a synthetic Montoya host, never native Burp. */
public final class AutoLlmPanelTest extends WorkbenchTest {
 public static void main(String[] args)throws Exception {
  Map<String,String> saved=new HashMap<>();
  var data=proxy(PersistedObject.class,(p,m,a)->switch(m.getName()){case "getString"->saved.get((String)a[0]);case "setString"->{saved.put((String)a[0],(String)a[1]);yield null;}default->def(m);});
  var persistence=proxy(Persistence.class,(p,m,a)->m.getName().equals("extensionData")?data:def(m));
  List<HttpRequest> sent=Collections.synchronizedList(new ArrayList<>());AtomicBoolean redirectsDisabled=new AtomicBoolean();
  var http=proxy(Http.class,(p,m,a)->{if(m.getName().equals("sendRequest")){HttpRequest q=(HttpRequest)a[0];sent.add(q);return AuthRecordTest.rr(q,response(q.headerValue("Cookie").equals("A")?200:403,"{\"owner\":\"A\"}"));}return def(m);});
  MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->switch(m.getName()){case "http"->http;case "persistence"->persistence;default->def(m);});
  ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->switch(m.getName()){
   case "httpRequestFromUrl"->request((String)a[0],"GET",true,List.of());
   case "requestOptions"->proxy(RequestOptions.class,(pp,mm,aa)->{if(mm.getName().equals("withRedirectionMode"))redirectsDisabled.set(aa[0]==RedirectionMode.NEVER);return pp;});
   case "httpRequestResponse"->AuthRecordTest.rr((HttpRequest)a[0],(burp.api.montoya.http.message.responses.HttpResponse)a[1]);
   default->def(m);
  });
  AtomicReference<AuthComparePanel> auth=new AtomicReference<>();AtomicReference<AutoLlmPanel> box=new AtomicReference<>();AtomicInteger models=new AtomicInteger();AtomicBoolean risk=new AtomicBoolean();
  edt(()->{
   auth.set(new AuthComparePanel(api,List::of));
   auth.get().slots[0]=new AuthComparePanel.Candidate(1,"a","https://auto.test",Map.of("cookie","A"),1,"");auth.get().slots[1]=new AuthComparePanel.Candidate(2,"b","https://auto.test",Map.of("cookie","B"),1,"");
   box.set(new AutoLlmPanel(api,auth.get(),null,(settings,input,call)->{models.incrementAndGet();check(!input.preview().contains("Cookie: A"),"model input masks actual authentication");return AutoLlmEngineTest.plan(risk.get());}));
   var panel=box.get();panel.origin.setText("https://auto.test");panel.paths.setText("/profile");panel.interval.setValue(1);panel.budget.setValue(3);
   panel.start.doClick();check(panel.state.getText().contains("확인"),"setup declarations required");
   panel.readOnly.setSelected(true);panel.privateData.setSelected(true);panel.start.doClick();
  });
  var panel=box.get();var engine=(AutoLlmEngine)get(panel,"engine");
  HttpRequest q=request("https://auto.test/profile?id=1","GET",true,List.of(header("Cookie","A")));var response=response(200,"{\"owner\":\"A\"}");registerTrafficPacket(q,response);
  try(var store=new DiskTrafficStore()){
   var ref=store.append("now","Proxy",q,response);panel.offer(ref);AutoLlmEngineTest.await(()->engine.snapshot().results().size()==1);edt(()->{});
   check(sent.size()==3,"panel sends exactly three production requests");check(sent.get(0).headerValue("Cookie").equals("A")&&sent.get(1).headerValue("Cookie").equals("B")&&sent.get(2).headerValue("Cookie").equals("A"),"credential replacement follows A B A");
   check(sent.get(1).url().equals(q.url())&&q.headerValue("Cookie").equals("A"),"original URL and source credentials preserved");check(redirectsDisabled.get(),"no redirect escape");check(panel.rows.getRowCount()==1,"background results visible");
   panel.offer(ref);Thread.sleep(100);check(models.get()==1,"duplicate event ignored");
   edt(()->panel.stop.doClick());AutoLlmEngineTest.await(()->!engine.snapshot().busy());
   risk.set(true);edt(()->panel.start.doClick());var pending=(AutoLlmEngine)get(panel,"engine");panel.offer(ref);AutoLlmEngineTest.await(()->pending.snapshot().results().size()==1);edt(()->{panel.table.setRowSelectionInterval(0,0);});
   check(sent.size()==3&&panel.approve.isEnabled(),"availability risk remains unsent and has per-item approval");
   edt(()->capture(panel));
   edt(()->{auth.get().slots[1]=new AuthComparePanel.Candidate(3,"c","https://auto.test",Map.of("cookie","C"),1,"");});
   Thread.sleep(700);AutoLlmEngineTest.await(()->!pending.snapshot().busy());check(!pending.approve(pending.snapshot().results().isEmpty()?"missing":pending.snapshot().results().get(0).id()),"account changes invalidate approvals");check(sent.size()==3,"account change never resumes requests");
  }
  edt(()->{
   panel.close();auth.get().close();
  });
  System.out.println("AutoLlmPanelTest: "+checks+" checks passed");
 }
 static void capture(AutoLlmPanel panel){
   panel.setSize(850,700);UiLayoutTest.layout(panel);UiLayoutTest.layout(panel);
   String out=System.getProperty("auto.llm.capture");if(out!=null)try{BufferedImage im=new BufferedImage(850,700,BufferedImage.TYPE_INT_RGB);var g=im.createGraphics();panel.printAll(g);g.dispose();ImageIO.write(im,"png",Path.of(out).toFile());}catch(Exception e){throw new RuntimeException(e);}
 }
}
