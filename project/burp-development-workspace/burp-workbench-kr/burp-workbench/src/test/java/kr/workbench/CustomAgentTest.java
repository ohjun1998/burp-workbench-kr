package kr.workbench;

import burp.api.montoya.*;
import burp.api.montoya.core.*;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.*;
import burp.api.montoya.http.message.requests.*;
import burp.api.montoya.internal.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Production transmission and API boundary with synthetic Burp, zero actual network requests. */
public final class CustomAgentTest extends WorkbenchTest {
  public static void main(String[] args)throws Exception {
    List<HttpRequest> sent=new ArrayList<>();AtomicInteger status=new AtomicInteger(200);AtomicBoolean redirects=new AtomicBoolean();
    var http=proxy(Http.class,(p,m,a)->{if(m.getName().equals("sendRequest")){sent.add((HttpRequest)a[0]);return AuthRecordTest.rr((HttpRequest)a[0],response(status.get(),"ok"));}return def(m);});
    var host=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http")?http:def(m));
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->m.getName().equals("requestOptions")?proxy(RequestOptions.class,(pp,mm,aa)->{if(mm.getName().equals("withRedirectionMode"))redirects.set(aa[0]==RedirectionMode.NEVER);return pp;}):def(m));
    AtomicBoolean scope=new AtomicBoolean(true),approve=new AtomicBoolean(false),valid=new AtomicBoolean(true);AtomicInteger approvals=new AtomicInteger();
    var gate=new CustomAgentGate(host,URI.create("http://127.0.0.1:8731/v1"),q->{approvals.incrementAndGet();return approve.get();},s->{},q->scope.get());
    HttpRequest q=request("https://target.test/profile?id=1","GET",true,List.of(header("Cookie","original")));
    blocked(()->gate.send(q),"starts with target sends disabled");check(sent.isEmpty(),"opening never sends to target");
    gate.start("https://target.test",2,4,1000,r->r.withHeader("Cookie","test-account"),()->{if(!valid.get())throw new IllegalStateException("account changed");});
    blocked(()->gate.send(q),"denied approval cannot send");check(sent.isEmpty()&&gate.targets()==0,"denial consumes no target budget");
    scope.set(false);blocked(()->gate.send(q),"out of scope denied before approval");check(approvals.get()==1,"scope denial does not ask approval");scope.set(true);
    blocked(()->gate.send(request("https://foreign.test/profile","GET",true,List.of())),"foreign origin denied");
    blocked(()->gate.send(request(q.url(),"GET",true,List.of(header("Host","foreign.test")))),"Host mismatch denied");
    approve.set(true);gate.send(q);check(sent.size()==1&&sent.get(0).headerValue("Cookie").equals("test-account"),"selected test identity is applied");check(q.headerValue("Cookie").equals("original"),"original packet is unchanged");check(redirects.get(),"target redirect following is disabled");
    valid.set(false);blocked(()->gate.send(q),"changed test account invalidates approval boundary");valid.set(true);
    gate.send(q);check(approvals.get()==3,"each target request needs a fresh approval");blocked(()->gate.send(q),"target budget exhaustion prevents send");check(sent.size()==2&&!gate.running(),"budget exhausted at exactly two requests");
    gate.start("https://target.test",5,4,1000,r->r,()->{});status.set(429);blocked(()->gate.send(q),"429 stops subsequent traffic");check(!gate.running(),"rate limit leaves gate stopped");status.set(200);
    var pending=new CustomAgentGate(host,gate.modelBase,r->{scope.set(false);return true;},s->{},r->scope.get());scope.set(true);pending.start("https://target.test",2,4,1000,r->r,()->{});
    int before=sent.size();blocked(()->pending.send(q),"scope rechecked after approval");check(sent.size()==before,"scope revocation sends nothing");pending.close();scope.set(true);
    var stopping=new AtomicReference<CustomAgentGate>();var stopGate=new CustomAgentGate(host,gate.modelBase,r->{stopping.get().stop();return true;},s->{},r->true);stopping.set(stopGate);stopGate.start("https://target.test",2,4,1000,r->r,()->{});
    blocked(()->stopGate.send(q),"stop during approval invalidates request");check(sent.size()==before,"stop prevents pending send");stopGate.close();
    HttpRequest model=request("http://127.0.0.1:8731/v1/chat/completions","POST",false,List.of());gate.send(model);check(sent.size()==before+1,"local model remains available without arming targets");
    blocked(()->gate.send(request("http://127.0.0.1:8731/admin","GET",false,List.of())),"model origin is not a general loopback bypass");
    blocked(()->gate.send(request("http://127.0.0.1:8731/v1/chat/completions?x=1","POST",false,List.of())),"model query parameters blocked");
    var bridge=new CustomAgentBridge(host,gate,"test-model",component->{});var api=bridge.api();
    blocked(()->api.http().sendRequests(List.of(q)),"batch sends cannot bypass approvals");
    blocked(()->api.http().createRequestEngine(),"request engine cannot bypass gate");
    blocked(()->api.intruder(),"Intruder automation outside budget is blocked");
    blocked(()->api.websockets(),"unbudgeted WebSocket path blocked");
    var prefs=bridge.memoryPreferences();check(prefs.getString("backend.preferred").equals("openai-compatible"),"default backend is local compatible API");
    check(prefs.getString("openai.compat.url").equals(gate.modelBase.toString()),"local endpoint carried to upstream settings");
    check(Boolean.FALSE.equals(prefs.getBoolean("mcp.enabled")),"MCP begins disabled");
    prefs.setString("test","secret");check(prefs.stringKeys().contains("test"),"in-memory preference API supports typed keys");prefs.deleteString("test");check(prefs.getString("test")==null,"memory preferences delete works");
    Path wrong=Files.createTempFile("custom-agent-wrong-",".jar");Files.writeString(wrong,"wrong");try{CustomAgentBridge.verify(wrong);throw new AssertionError();}catch(SecurityException expected){check(true,"upstream JAR checksum enforced");}finally{Files.delete(wrong);}
    bridge.close();blocked(()->gate.send(model),"unload also blocks model calls");blocked(()->api.http(),"unloaded API refuses access");bridge.close();check(true,"close is idempotent");
    System.out.println("CustomAgentTest: "+checks+" checks passed");
  }
  static void blocked(Runnable action,String name){try{action.run();throw new AssertionError(name);}catch(RuntimeException e){check(true,name);}}
}
