package kr.workbench;
import burp.api.montoya.*;
import burp.api.montoya.http.message.requests.*;
import burp.api.montoya.internal.*;
import burp.api.montoya.repeater.*;
import javax.swing.*;
import java.util.*;
import java.util.concurrent.atomic.*;
public final class JsAnalysisPanelTest {
 static int checks;static void check(boolean b,String text){if(!b)throw new AssertionError(text);checks++;System.out.println("PASS "+text);}
 static void edt(Runnable r)throws Exception{SwingUtilities.invokeAndWait(r);}
 static void waitDone(JsAnalysisPanel p)throws Exception{for(int i=0;i<100;i++){Thread.sleep(50);edt(()->{});if(p.idle())return;}throw new AssertionError("analysis worker timeout");}
 public static void main(String[] args)throws Exception{
  AtomicInteger repeated=new AtomicInteger();AtomicReference<HttpRequest> last=new AtomicReference<>();var repeater=WorkbenchTest.proxy(Repeater.class,(o,m,a)->{if(m.getName().equals("sendToRepeater")){repeated.incrementAndGet();last.set((HttpRequest)a[0]);}return WorkbenchTest.def(m);});var api=WorkbenchTest.proxy(MontoyaApi.class,(o,m,a)->m.getName().equals("repeater")?repeater:WorkbenchTest.def(m));
  ObjectFactoryLocator.FACTORY=WorkbenchTest.proxy(MontoyaObjectFactory.class,(o,m,a)->{if(m.getReturnType()==HttpRequest.class&&a.length==1&&a[0] instanceof String url)return WorkbenchTest.request(url,"GET",url.startsWith("https://allowed.test/"),List.of());return WorkbenchTest.def(m);});
  JsAnalysisPanel p=new JsAnalysisPanel(api,()->{});String token="ghp_"+"A1b2C3d4".repeat(5);edt(()->p.load("fetch('/users?token=QUERY_SECRET');const apiKey='"+token+"';","https://allowed.test/main.js?token=SOURCE_SECRET"));waitDone(p);
  check(p.endpoints.getRowCount()==1&&p.secrets.getRowCount()==1,"manual AST result tables");check(!p.maskedReport().contains(token)&&!p.maskedReport().contains("QUERY_SECRET")&&!p.maskedReport().contains("SOURCE_SECRET"),"masked report excludes secrets and URL query values");
  edt(()->{p.urlTable.setRowSelectionInterval(0,0);p.toRepeater.doClick();});check(repeated.get()==1&&last.get().url().equals("https://allowed.test/users?token=REPLACE_ME")&&last.get().method().equals("GET"),"explicit action prepares placeholder query in Repeater");
  edt(()->p.load("fetch('https://outside.test/admin');","https://allowed.test/main.js"));waitDone(p);edt(()->{p.urlTable.setRowSelectionInterval(0,0);p.toRepeater.doClick();});check(repeated.get()==1&&p.status.getText().contains("Scope"),"out-of-scope Repeater preparation blocked");
  edt(()->p.load("fetch('/users/'+userId);","https://allowed.test/main.js"));waitDone(p);edt(()->{p.urlTable.setRowSelectionInterval(0,0);p.toRepeater.doClick();});check(repeated.get()==1&&p.status.getText().contains("동적"),"dynamic endpoint preparation blocked");
  edt(()->p.clear());var request=WorkbenchTest.request("https://allowed.test/app.js","GET",false,List.of());var response=WorkbenchTest.response(200,"fetch('/automatic');");edt(()->p.offer(request,response));check(p.sources.getItemCount()==0,"passive out-of-scope response ignored");
  var inScope=WorkbenchTest.request("https://allowed.test/app.js","GET",true,List.of());edt(()->p.offer(inScope,response));waitDone(p);check(p.endpoints.getRowCount()==1&&repeated.get()==1,"passive analysis without request or Repeater action");check(p.input.getText().isEmpty(),"automatic analysis does not display raw source");
  edt(()->p.offer(inScope,WorkbenchTest.response(200,"fetch('/changed');")));waitDone(p);check(p.sources.getItemCount()==1&&p.maskedReport().contains("/changed"),"changed JS source reanalyzed under same source URL");
  edt(()->{p.load("fetch('/discard');","https://allowed.test/clear.js");p.clear();});waitDone(p);check(p.sources.getItemCount()==0&&p.endpoints.getRowCount()==0,"clear prevents stale async result restoration");
  edt(()->p.close());check(p.input.getText().isEmpty()&&p.secrets.getRowCount()==0,"close clears raw source and secret rows");System.out.println("JsAnalysisPanelTest: "+checks+" checks passed");
 }
}
