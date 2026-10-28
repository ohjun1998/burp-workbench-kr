package kr.workbench;
import burp.api.montoya.*;
import burp.api.montoya.http.message.requests.*;
import burp.api.montoya.internal.*;
import burp.api.montoya.repeater.*;
import javax.swing.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
public final class JsAnalysisPanelTest {
 static int checks;static void check(boolean b,String text){if(!b)throw new AssertionError(text);checks++;System.out.println("PASS "+text);}
 static void edt(Runnable r)throws Exception{SwingUtilities.invokeAndWait(r);}
 static void waitDone(JsAnalysisPanel p)throws Exception{
  // FIFO worker completion precedes the EDT barrier. Checking idle after an earlier
  // EDT barrier can race with the worker posting its render callback immediately afterwards.
  var worker=(ThreadPoolExecutor)WorkbenchTest.get(p,"worker");
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
  while(System.nanoTime()<deadline){
    worker.submit(()->{}).get(5,TimeUnit.SECONDS);
    var idle=new java.util.concurrent.atomic.AtomicBoolean();edt(()->idle.set(p.idle()));
    if(idle.get())return;Thread.sleep(10);
  }
  throw new AssertionError("analysis worker still active after bounded worker/EDT barriers");
 }
 public static void main(String[] args)throws Exception{
  AtomicInteger repeated=new AtomicInteger();AtomicReference<HttpRequest> last=new AtomicReference<>();var repeater=WorkbenchTest.proxy(Repeater.class,(o,m,a)->{if(m.getName().equals("sendToRepeater")){repeated.incrementAndGet();last.set((HttpRequest)a[0]);}return WorkbenchTest.def(m);});var api=WorkbenchTest.proxy(MontoyaApi.class,(o,m,a)->m.getName().equals("repeater")?repeater:WorkbenchTest.def(m));
  ObjectFactoryLocator.FACTORY=WorkbenchTest.proxy(MontoyaObjectFactory.class,(o,m,a)->{if(m.getReturnType()==HttpRequest.class&&a.length==1&&a[0] instanceof String url)return WorkbenchTest.request(url,"GET",url.startsWith("https://allowed.test/"),List.of());return WorkbenchTest.def(m);});
  AtomicReference<JsAnalysisPanel> panel=new AtomicReference<>();edt(()->panel.set(new JsAnalysisPanel(api,()->{})));JsAnalysisPanel p=panel.get();String token="ghp_"+"A1b2C3d4".repeat(5);edt(()->p.load("fetch('/users?token=QUERY_SECRET');const apiKey='"+token+"';","https://allowed.test/main.js?token=SOURCE_SECRET"));waitDone(p);
  check(p.endpoints.getRowCount()==1&&p.secrets.getRowCount()==1,"manual AST result tables");check(!p.maskedReport().contains(token)&&!p.maskedReport().contains("QUERY_SECRET")&&!p.maskedReport().contains("SOURCE_SECRET"),"masked report excludes secrets and URL query values");
  edt(()->{p.urlTable.setRowSelectionInterval(0,0);p.prepareRepeater();});check(repeated.get()==0&&p.repeater.sessions.size()==1&&wire(p,0).startsWith("GET /users?token=REPLACE_ME HTTP/1.1"),"explicit action opens an editable Repeater draft with placeholder query");
  edt(()->p.load("fetch('https://outside.test/admin');","https://allowed.test/main.js"));waitDone(p);edt(()->{p.urlTable.setRowSelectionInterval(0,0);p.prepareRepeater();});check(repeated.get()==0&&p.repeater.sessions.size()==2,"out-of-scope candidates can be inspected without export or transmission");
  edt(()->p.load("fetch('/users/'+userId);","https://allowed.test/main.js"));waitDone(p);edt(()->{p.urlTable.setRowSelectionInterval(0,0);p.prepareRepeater();});check(repeated.get()==0&&wire(p,2).contains("REPLACE_PATH"),"dynamic candidates open immediately with an explicit unresolved path");
  edt(()->p.clear());var request=WorkbenchTest.request("https://allowed.test/app.js","GET",false,List.of());var response=WorkbenchTest.response(200,"fetch('/automatic');");edt(()->p.offer(request,response));check(p.sources.getItemCount()==0,"passive out-of-scope response ignored");
  var inScope=WorkbenchTest.request("https://allowed.test/app.js","GET",true,List.of());edt(()->p.offer(inScope,response));waitDone(p);check(p.endpoints.getRowCount()==1&&repeated.get()==0&&p.repeater.sessions.size()==3,"passive analysis without request or Repeater action");check(p.input.getText().isEmpty(),"automatic analysis does not display raw source");
  edt(()->p.offer(inScope,WorkbenchTest.response(200,"fetch('/changed');")));waitDone(p);check(p.sources.getItemCount()==1&&p.maskedReport().contains("/changed"),"changed JS source reanalyzed under same source URL");
  edt(()->{p.load("fetch('/discard');","https://allowed.test/clear.js");p.clear();});waitDone(p);check(p.sources.getItemCount()==0&&p.endpoints.getRowCount()==0,"clear prevents stale async result restoration");
  edt(()->p.close());check(p.input.getText().isEmpty()&&p.secrets.getRowCount()==0,"close clears raw source and secret rows");System.out.println("JsAnalysisPanelTest: "+checks+" checks passed");
 }
 static String wire(JsAnalysisPanel panel,int session){return new String(panel.repeater.sessions.get(session).entries.get(0).request(),java.nio.charset.StandardCharsets.UTF_8);}
}
