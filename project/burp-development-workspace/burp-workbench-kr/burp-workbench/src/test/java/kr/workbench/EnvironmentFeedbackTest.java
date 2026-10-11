package kr.workbench;
import burp.api.montoya.*;import burp.api.montoya.http.*;import burp.api.montoya.http.message.*;import burp.api.montoya.http.message.requests.*;import burp.api.montoya.internal.*;import java.util.*;import java.util.List;import java.util.concurrent.atomic.*;
public final class EnvironmentFeedbackTest extends WorkbenchTest {
 public static void main(String[] args)throws Exception {
  List<String> sent=Collections.synchronizedList(new ArrayList<>());AtomicBoolean limit=new AtomicBoolean();
  ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->switch(m.getName()){case "httpRequestFromUrl"->request((String)a[0],"GET",true,List.of());case "requestOptions"->proxy(RequestOptions.class,(pp,mm,aa)->pp);default->def(m);});
  var http=proxy(Http.class,(p,m,a)->{if(!m.getName().equals("sendRequest"))return def(m);HttpRequest q=(HttpRequest)a[0];sent.add(q.url());var s=response(limit.get()?429:200,"<html>SPA shell</html>");return proxy(HttpRequestResponse.class,(pp,mm,aa)->switch(mm.getName()){case "request"->q;case "response"->s;default->def(mm);});});
  var api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http")?http:def(m));var box=new AtomicReference<EnvironmentPanel>();edt(()->{var panel=new EnvironmentPanel(api);box.set(panel);EnvironmentBatchTest.fill(panel,"https://test/a","https://test/b","https://test/actuator/heapdump");panel.start();});var panel=box.get();EnvironmentBatchTest.idle(panel);
  check(sent.size()==4&&sent.get(0).contains("/wb-missing-"),"two stable reference probes plus two candidates");check(sent.stream().noneMatch(u->u.endsWith("heapdump")),"batch excludes heavy diagnostics");check(panel.packets.get("https://test/a").state().contains("없는 경로와 동일"),"SPA shell marked soft 404 in batch");
  edt(panel::start);check(sent.size()==4,"verified candidates not resent by default");
  sent.clear();limit.set(true);edt(()->{panel.compareMissing.setSelected(false);EnvironmentBatchTest.fill(panel,"https://test/rate","https://test/later");panel.start();});EnvironmentBatchTest.idle(panel);check(sent.size()==1&&panel.packets.get("https://test/rate").state().contains("요청 제한"),"429 stops remaining candidates");
  edt(panel::close);System.out.println("EnvironmentFeedbackTest: "+checks+" checks passed");
 }
}
