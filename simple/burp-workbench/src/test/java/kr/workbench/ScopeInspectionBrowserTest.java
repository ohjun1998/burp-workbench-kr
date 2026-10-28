package kr.workbench;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Command lease, exact Scope and metadata privacy without a browser or target network. */
public final class ScopeInspectionBrowserTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    var received=new ArrayList<Map<String,Object>>();var enabled=new AtomicBoolean(true);
    var browser=new ScopeInspectionBrowser(received::add,url->enabled.get());
    long expires=System.currentTimeMillis()+60000;
    browser.begin("run-1","https://browser.test/app",List.of("https://browser.test/app"),List.of("https://browser.test/app/private"),10,20,expires);
    check(browser.active()&&browser.snapshot().state().equals("WAITING"),"explicit browser task waits for paired helper");
    check(browser.command("https://other.test").get("state").equals("idle"),"other origins cannot obtain the task");
    var command=browser.command("https://browser.test");
    check(command.get("state").equals("run")&&command.get("runId").equals("run-1"),"paired origin receives exact run and Scope command");
    check(browser.command("https://browser.test").get("state").equals("running"),"command is leased once, avoiding two helpers launching duplicate tabs");
    check(browser.snapshot().state().equals("DISPATCHED"),"dispatch is distinct from actual browser start");
    check(Boolean.FALSE.equals(command.get("httpProfileShared")),"browser session never claims selected HTTP profile identity");
    check(command.keySet().stream().noneMatch(k->k.toLowerCase().matches(".*(?:cookie|password|token|headers).*")),"command includes no target credentials");
    browser.event(event("run-1","started","https://browser.test/app"));
    check(browser.snapshot().state().equals("RUNNING"),"authenticated helper acknowledgement marks actual start");
    var api=event("run-1","api","https://browser.test/app/api?token=PRIVATE_SESSION#/orders");
    api.put("page","https://browser.test/app?session=PRIVATE_SESSION#/home");api.put("label","token=PRIVATE_SESSION");
    api.put("headers",Map.of("Cookie","PRIVATE_SESSION"));api.put("body","PRIVATE_SESSION");api.put("method","GET");
    api.put("routeId","a".repeat(64));api.put("requiresQuery",true);api.put("queryNames",List.of("token"));
    browser.event(api);
    var result=received.get(received.size()-1);
    check(result.get("url").equals("https://browser.test/app/api")&&result.get("page").equals("https://browser.test/app"),"metadata URL removes query and fragment values");
    check(!result.toString().contains("PRIVATE_SESSION")&&!result.containsKey("headers")&&!result.containsKey("body"),"metadata allowlist and masking exclude private credentials and bodies");
    check(Boolean.TRUE.equals(result.get("requiresQuery")),"query-dependent browser API remains observation rather than a guessed HTTP replay");
    check(result.get("actor").equals("browser-session")&&!Boolean.TRUE.equals(result.get("httpProfileShared")),"API observation actor remains separate from HTTP comparison account");
    int count=received.size();browser.event(api);
    check(received.size()==count,"duplicate browser observations are coalesced without losing original HTTP packets");
    browser.event(event("old-run","route","https://browser.test/app/old"));
    check(received.size()==count,"stale browser run cannot enter new task results");
    for(String url:List.of("https://other.test/app","https://browser.test/apple","https://browser.test/app/private-data","https://browser.test/app/%2e%2e/admin","https://browser.test/app/%252e%252e/admin"))browser.event(event("run-1","route",url));
    check(received.size()==count&&browser.snapshot().excluded()==5,"current Scope and conservative exclusion prefixes reject every out-of-bound route");
    var second=event("run-1","route","https://browser.test/app");second.put("routeId","b".repeat(64));browser.event(second);
    var third=event("run-1","route","https://browser.test/app");third.put("routeId","c".repeat(64));browser.event(third);
    check(received.size()==count+2,"same pathname with distinct SPA route IDs remains separate");
    enabled.set(false);
    check(browser.command("https://browser.test").get("state").equals("cancel")&&!browser.active(),"live Scope revocation cancels leased browser exploration");
    browser.close();check(browser.snapshot().state().equals("CLOSED"),"unload closes browser task state");
    boolean closedRejected=false;
    try {browser.begin("run-2","https://browser.test/app",List.of("https://browser.test/app"),List.of(),10,20,System.currentTimeMillis()+1000);}
    catch(IllegalStateException expected){closedRejected=true;}
    check(closedRejected,"closed browser task cannot be restarted");
    try(var bare=new ScopeInspectionBrowser(value->{})) {
      bare.begin("root","https://root.test",List.of("https://root.test/"),List.of(),5,10,System.currentTimeMillis()+10000);
      check(bare.active(),"bare origin and slash root share the same browser Scope boundary");
      bare.event(event("root","done","https://root.test/"));
    }
    try(var another=new ScopeInspectionBrowser(value->{})) {
      rejects(()->another.begin("bad","https://browser.test/apple",List.of("/app"),List.of(),5,10,System.currentTimeMillis()+10000),"start cannot broaden a path-boundary Scope");
      rejects(()->another.begin("bad","https://browser.test/app",List.of("https://other.test/app"),List.of(),5,10,System.currentTimeMillis()+10000),"cross-origin browser rules are rejected");
      rejects(()->another.begin("bad","https://browser.test/app",List.of("/app"),List.of(),101,10,System.currentTimeMillis()+10000),"page budget is bounded");
      rejects(()->another.begin("bad","https://browser.test/app",List.of("/app"),List.of(),5,10,System.currentTimeMillis()-1),"expired browser command never becomes active");
    }
    var lifecycle=new ArrayList<Map<String,Object>>();
    try(var observed=new ScopeInspectionBrowser(lifecycle::add)) {
      observed.begin("network","https://browser.test/app",List.of("/app"),List.of(),5,10,System.currentTimeMillis()+10000);
      var request=event("network","api","https://browser.test/app/api?token=PRIVATE_QUERY");
      request.put("method","GET");request.put("networkStage","request");request.put("requestId","d".repeat(64));request.put("resourceType","xmlhttprequest");request.put("frameId",2);request.put("pendingRequests",1);
      request.put("headers",Map.of("Cookie","PRIVATE_COOKIE"));request.put("body","PRIVATE_BODY");observed.event(request);
      var response=new LinkedHashMap<String,Object>(request);response.put("networkStage","response");response.put("statusCode",403);response.put("responses",1);response.put("pendingRequests",0);response.put("elapsedMillis",250);response.put("fromCache",true);observed.event(response);
      check(lifecycle.size()==2,"response stage survives request metadata deduplication");
      check(lifecycle.get(1).get("statusCode").equals(403)&&lifecycle.get(1).get("requestId").equals(lifecycle.get(0).get("requestId")),"actual response status is linked by a bounded hashed request ID");
      check(observed.snapshot().responses()==1&&observed.snapshot().pendingRequests()==0,"network counters retain response completion and clear pending count");
      check(!lifecycle.toString().contains("PRIVATE_")&&!lifecycle.get(1).containsKey("body"),"new completion telemetry never forwards raw credentials or response bodies");
      var invalid=new LinkedHashMap<String,Object>(response);invalid.put("requestId","raw-private-id");observed.event(invalid);
      check(lifecycle.size()==2,"bridge rejects unhashed request correlation values");
      invalid=new LinkedHashMap<>(response);invalid.put("errorCode","PRIVATE_ERROR");observed.event(invalid);
      check(lifecycle.size()==2,"bridge accepts only bounded network error categories");
      for(Object statusValue:Arrays.asList(null,0,-1,99,600,200.5,Double.NaN,Double.POSITIVE_INFINITY,"200")){
        invalid=new LinkedHashMap<>(response);invalid.put("requestId","e".repeat(64));
        if(statusValue==null)invalid.remove("statusCode");else invalid.put("statusCode",statusValue);
        observed.event(invalid);
        check(lifecycle.size()==2&&observed.snapshot().responses()==1,"invalid HTTP response status cannot cross browser bridge or promote coverage: "+statusValue);
      }
      var error=new LinkedHashMap<String,Object>(request);error.put("networkStage","error");error.put("errorCode","TIMED_OUT");error.put("networkErrors",1);error.put("pendingRequests",0);observed.event(error);
      check(lifecycle.size()==3&&lifecycle.get(2).get("statusCode").equals(0)&&observed.snapshot().networkErrors()==1,"callback error remains HTTP-unconfirmed rather than a response status");
      observed.event(response);check(lifecycle.size()==3,"duplicate completion is coalesced by request ID and stage");
    }
    System.out.println("ScopeInspectionBrowserTest: "+checks+" checks passed (metadata adapter, no HTTP)");
  }
  static Map<String,Object> event(String id,String kind,String url){var value=new LinkedHashMap<String,Object>();value.put("type","inspection");value.put("runId",id);value.put("kind",kind);value.put("url",url);return value;}
}
