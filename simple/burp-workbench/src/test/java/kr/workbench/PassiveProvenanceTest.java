package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Discovery provenance and actual GET control flow with an isolated API adapter. */
public final class PassiveProvenanceTest extends WorkbenchTest {
  public static void main(String[] args)throws Exception{
    check(PassiveUrls.sourceKind("Burp History POST")==PassiveUrls.SourceKind.OBSERVED,
        "History methods are direct request observations");
    check(List.of("JS GET","HTML ?","HTML form POST","OpenAPI DELETE").stream()
        .allMatch(source->PassiveUrls.sourceKind(source)==PassiveUrls.SourceKind.STATIC),
        "JS HTML forms and OpenAPI declarations remain static inference");
    check(List.of("waybackurls","gau","Wayback 내장").stream()
        .allMatch(source->PassiveUrls.sourceKind(source)==PassiveUrls.SourceKind.ARCHIVE),
        "archive collection does not imply current target observation");
    check(PassiveUrls.sourceKind("가져오기")==PassiveUrls.SourceKind.MANUAL
        &&PassiveUrls.sourceKind("이전 DB")==PassiveUrls.SourceKind.MANUAL,
        "manual lists and previous DB cannot inherit unprovided provenance");
    check(PassiveUrls.sourceKind("custom GET")==PassiveUrls.SourceKind.UNKNOWN
        &&PassiveUrls.sourceMethod("custom GET").isEmpty(),
        "unrecognized source labels cannot manufacture a discovered GET");
    var legacy=new PassiveScanPanel.Item(1,"https://example.com/legacy");legacy.addSource("custom POST");
    check(legacy.observedMethods.isEmpty()&&legacy.inferredMethods.isEmpty()&&legacy.methods.isEmpty()
        &&legacy.unattributedMethods.equals(Set.of("POST"))&&legacy.groupingMethods().equals("POST"),
        "legacy adapter method hints preserve representative grouping without claiming observed provenance");
    check(PassiveUrls.sourceMethod("HTML form POST").equals("POST")
        &&PassiveUrls.sourceMethod("HTML form").isEmpty()
        &&PassiveUrls.sourceMethod("HTML ?").isEmpty(),"unknown HTML methods remain unconfirmed");
    check(PassiveUrls.sourceMethod("Burp History PROPFIND").equals("PROPFIND")
        &&PassiveUrls.sourceMethod("Burp History get").equals("get"),
        "observed extension methods and method case are preserved");
    String escaped="https://example.com/a%2fb/../c?x=2&x=1&space=a+b&slash=%2F";
    check(PassiveUrls.url(escaped+"#local","example.com",false).equals(escaped),
        "URL collection preserves path query order duplicate keys and percent-encoding");

    AtomicInteger sends=new AtomicInteger(),createdAfterRace=new AtomicInteger();
    AtomicBoolean scopeRace=new AtomicBoolean(),failSend=new AtomicBoolean();
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->switch(m.getName()){
      case "httpRequestFromUrl"->request((String)a[0],"GET",!scopeRace.get()||createdAfterRace.incrementAndGet()==1,List.of());
      case "requestOptions"->proxy(RequestOptions.class,(pp,mm,aa)->pp);
      default->def(m);
    });
    var http=proxy(Http.class,(p,m,a)->{
      if(!m.getName().equals("sendRequest"))return def(m);
      HttpRequest q=(HttpRequest)a[0];sends.incrementAndGet();
      check(q.method().equals("GET"),"explicit verification sends GET independent of discovered method");
      if(failSend.get())throw new IllegalStateException("isolated transport failure");
      var reply=response(403,"{\"error\":\"denied\"}");
      return proxy(HttpRequestResponse.class,(pp,mm,aa)->switch(mm.getName()){
        case "request"->q;case "response"->reply;default->def(mm);
      });
    });
    MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http")?http:def(m));
    AtomicReference<PassiveScanPanel> reference=new AtomicReference<>();
    edt(()->reference.set(new PassiveScanPanel(api)));
    var panel=reference.get();String url="https://example.com/api/items?token=PRIVATE_VALUE";
    try{
      edt(()->{
        panel.target.setText("example.com");
        panel.add(url,"Burp History POST");panel.add(url,"OpenAPI GET");panel.add(url,"gau");panel.add(url,"가져오기");
        panel.table.setRowSelectionInterval(0,0);
        var item=panel.items.get(url);
        check(panel.items.size()==1&&item.sourceKinds.size()==4,"same exact URL retains all four evidence origins");
        check(item.observedMethods.equals(Set.of("POST"))&&item.inferredMethods.equals(Set.of("GET")),
            "observed POST is not conflated with an OpenAPI GET declaration");
        check(item.methods.equals(Set.of("GET","POST"))&&item.state.equals("미검증")&&!item.verificationAttempted,
            "discovery methods do not imply a GET verification result");
        check(item.tooltip().contains("직접 관측")&&item.tooltip().contains("정적 추정")&&item.tooltip().contains("GET 미검증"),
            "existing table tooltip explains evidence strength and unverified status");
        check(sends.get()==0,"collecting selecting and inspecting provenance sends no target request");
        panel.verify(List.of(url));
      });
      PassiveScanTest.idle(panel);
      edt(()->{
        var item=panel.items.get(url);
        check(item.verificationAttempted&&item.state.equals("HTTP 403")&&!item.lastVerification.isEmpty(),
            "explicit GET response has its own outcome and timestamp");
        check(item.observedMethods.equals(Set.of("POST"))&&item.inferredMethods.equals(Set.of("GET")),
            "GET verification never mutates historical or inferred method evidence");
        check(item.verificationSummary().contains("인증/권한·다른 메서드 별도 검토"),
            "GET reachability is not presented as authorization or POST validation");
        var rows=panel.exportRows();var row=rows.get(1);
        check(rows.get(0).contains("출처 유형")&&row.get(10).equals("POST")&&row.get(11).equals("GET")&&row.get(12).equals("예"),
            "export metadata separates origin observed method inferred method and GET attempt");
        check(!rows.toString().contains("PRIVATE_VALUE"),"metadata export does not expose query secret values");
        check(panel.packets.get(url)!=null&&panel.packets.get(url).source()!=null,"successful GET retains its bounded evidence source");
        scopeRace.set(true);createdAfterRace.set(0);panel.verify(List.of(url));
      });
      PassiveScanTest.idle(panel);
      edt(()->{
        var item=panel.items.get(url);
        check(sends.get()==1&&!item.verificationAttempted&&item.state.equals("Scope 제외"),
            "Scope removal between preflight and send records no new GET transmission");
        check(!panel.packets.containsKey(url)&&!panel.captureEvidence.isEnabled(),
            "Scope-excluded rerun cannot show or export the previous successful packet as its result");
        scopeRace.set(false);failSend.set(true);panel.verify(List.of(url));
      });
      PassiveScanTest.idle(panel);
      edt(()->{
        var item=panel.items.get(url);
        check(item.verificationAttempted&&item.state.equals("전송 오류")&&!item.verificationSummary().contains("HTTP 403"),
            "failed GET attempt does not retain an earlier successful status");
        panel.importUrls(escaped+"\n"+escaped.replace("x=2&x=1","x=1&x=2")+"\n"+escaped.replace("a%2fb","a/b"));
        check(panel.items.size()==4,"URL candidates with different query order or encoded path remain distinct");
        check(panel.model.getColumnCount()==10,"provenance metadata preserves the existing table layout");
      });
    }finally{edt(panel::close);}
    System.out.println("PassiveProvenanceTest: "+checks+" checks passed");
  }
}
