package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.internal.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import static kr.workbench.WorkbenchTest.*;

/** Real loopback cookies, explicit UI apply and fail-closed account comparison. */
public final class CookieSessionProbeTest {
  static int checks;
  static void verify(boolean yes,String message){if(!yes)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
  static void fails(Runnable action,String message){try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){verify(true,message);}}
  static final Set<String> AUTH=AuthComparePanel.DEFAULT_AUTH;
  static final String SECRET="VALUE_SESSION_SECRET";
  public static void main(String[] args)throws Exception {
    BountyFeaturesTest.factory();var baseFactory=ObjectFactoryLocator.FACTORY;AtomicBoolean noRedirects=new AtomicBoolean(),timeout=new AtomicBoolean();
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->{
      if(m.getName().equals("requestOptions"))return proxy(RequestOptions.class,(q,n,b)->{if(n.getName().equals("withRedirectionMode"))noRedirects.set(b[0]==RedirectionMode.NEVER);if(n.getName().equals("withResponseTimeout"))timeout.set(((Number)b[0]).intValue()==10000);return q;});
      return m.invoke(baseFactory,a);
    });
    AtomicReference<String> mode=new AtomicReference<>("single");AtomicInteger received=new AtomicInteger(),objects=new AtomicInteger();
    List<String> seen=new CopyOnWriteArrayList<>();
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{
      received.incrementAndGet();String cookie=Objects.toString(exchange.getRequestHeaders().getFirst("Cookie"),"");String auth=Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"),"");
      seen.add(exchange.getRequestURI().getPath()+"|"+cookie+"|"+auth);
      if(exchange.getRequestURI().getPath().equals("/object"))objects.incrementAndGet();
      boolean sid=cookie.contains("SID="+SECRET),device=cookie.contains("device=DEVICE_SECRET"),alt=cookie.contains("alternate=ALT_SECRET"),bearer=auth.equals("Bearer BEARER_SECRET");
      String scenario=mode.get();
      boolean logged=switch(scenario){case "joint"->sid&&device;case "either"->sid||alt;case "bearer"->bearer;case "mixed"->sid&&bearer;case "public"->true;case "expired","login200","wrong"->false;default->sid;};
      int status=logged?200:401;String body=logged?"{\"user\":{\"id\":\"alice\"}}":"{\"error\":\"login\"}";
      if(scenario.equals("login200")){status=200;body="{\"error\":\"login\"}";}
      if(scenario.equals("wrong")){status=200;body="{\"user\":{\"id\":\"mallory\"}}";}
      if(scenario.equals("redirect")){status=302;exchange.getResponseHeaders().set("Location","/object");body="";}
      if(scenario.equals("oversize")){status=200;body="x".repeat(CookieSessionProbe.MAX_RESPONSE+10);}
      if(scenario.equals("rotate"))exchange.getResponseHeaders().set("Set-Cookie","SID=ROTATED_SECRET; Path=/");
      if(scenario.equals("html")){status=200;body="<html>Signed in</html>";}
      if(scenario.equals("ratelimit")){status=429;body="{}";}
      byte[] data=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,data.length);exchange.getResponseBody().write(data);exchange.close();
    });server.start();
    String origin="http://127.0.0.1:"+server.getAddress().getPort(),normalized=origin;
    var client=java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
    AtomicBoolean tamper=new AtomicBoolean(),cancelled=new AtomicBoolean(),dropScope=new AtomicBoolean(),networkError=new AtomicBoolean();AtomicReference<WorkbenchScope> scopeRef=new AtomicReference<>();
    var http=proxy(Http.class,(p,m,a)->{
      if(!m.getName().equals("sendRequest"))return def(m);
      if(networkError.get())throw new IllegalArgumentException("NETWORK_SECRET");
      HttpRequest request=(HttpRequest)a[0];var outgoing=java.net.http.HttpRequest.newBuilder(URI.create(request.url())).GET();
      for(HttpHeader h:request.headers())if(!Set.of("host","content-length").contains(h.name().toLowerCase(Locale.ROOT)))outgoing.header(h.name(),h.value());
      var response=client.send(outgoing.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
      List<HttpHeader> headers=new ArrayList<>();response.headers().map().forEach((key,values)->values.forEach(value->headers.add(header(key,value))));
      if(dropScope.get())scopeRef.get().clear();
      return AuthRecordTest.rr(tamper.get()?request.withHeader("Cookie","SID=TAMPER_SECRET"):request,BountyFeaturesTest.rs(response.statusCode(),response.body(),headers));
    });
    var api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http")?http:def(m));
    WorkbenchScope scope=WorkbenchScope.install(api);scopeRef.set(scope);scope.add(origin+"/",true);
    var config=new CookieSessionProbe.Config(origin+"/me","$.user.id","alice");
    Map<String,String> profile=Map.of("cookie","SID="+SECRET+"; XSRF-TOKEN=CSRF_SECRET; tracking_id=TRACKING_SECRET");
    try {
      var single=CookieSessionProbe.analyze(api,config,profile,AUTH,normalized,cancelled::get);
      verify(single.valid()&&single.complete()&&single.cookieOnly(),"known user is verified using cookies and an anonymous control");
      verify(single.retained().equals(Set.of("SID")),"irrelevant cookies are removed from the verified sufficient combination");
      verify(single.findings().stream().anyMatch(f->f.name().equals("SID")&&f.result().equals("인증에 필요")),"SID is necessary under the configured identity check");
      verify(single.findings().stream().anyMatch(f->f.name().equals("XSRF-TOKEN")&&f.result().contains("이름 후보")),"CSRF-looking name stays a candidate rather than a proved purpose");
      verify(single.findings().stream().filter(f->f.name().equals("tracking_id")).noneMatch(f->f.result().contains("추적용")),"excluding a cookie does not claim that it is a tracking cookie");
      verify(!single.toString().contains("_SECRET")&&!single.toString().contains("alice")&&!config.toString().contains("alice"),"reports and configuration descriptions omit credentials and user values");
      verify(noRedirects.get()&&timeout.get(),"each validation request disables redirects and has a response timeout");
      verify(seen.stream().allMatch(s->s.startsWith("/me|")),"inspection sends only the explicit validation GET endpoint");
      verify(single.trials().size()<=CookieSessionProbe.MAX_REQUESTS,"experiment requests stay within the explicit budget");
      mode.set("joint");var joint=CookieSessionProbe.analyze(api,config,Map.of("cookie","SID="+SECRET+"; device=DEVICE_SECRET; junk=JUNK_SECRET"),AUTH,normalized,()->false);
      verify(joint.valid()&&joint.retained().equals(Set.of("SID","device")),"two jointly required cookies remain together");
      verify(joint.findings().stream().filter(f->Set.of("SID","device").contains(f.name())).allMatch(f->f.result().equals("조합에 필요")),"joint credentials are identified without claiming either works alone");
      mode.set("either");var either=CookieSessionProbe.analyze(api,config,Map.of("cookie","SID="+SECRET+"; alternate=ALT_SECRET"),AUTH,normalized,()->false);
      verify(either.valid()&&either.retained().size()==1,"alternative sufficient cookies are reduced sequentially rather than all removed together");
      verify(either.findings().stream().allMatch(f->f.alone().equals("신원 일치")),"multiple independently valid cookies are reported individually");
      mode.set("bearer");var bearer=CookieSessionProbe.analyze(api,config,Map.of("cookie","junk=JUNK_SECRET","authorization","Bearer BEARER_SECRET"),AUTH,normalized,()->false);
      verify(bearer.valid()&&!bearer.cookieOnly()&&bearer.retained().equals(Set.of("junk")),"bearer authentication cannot be misattributed to a cookie");
      verify(bearer.findings().stream().anyMatch(f->f.result().equals("쿠키 없이도 신원 확인")),"other authentication header contribution is visible");
      mode.set("mixed");var mixed=CookieSessionProbe.analyze(api,config,Map.of("cookie","SID="+SECRET,"authorization","Bearer BEARER_SECRET"),AUTH,normalized,()->false);
      verify(mixed.valid()&&!mixed.cookieOnly()&&mixed.findings().stream().anyMatch(f->f.result().contains("쿠키+다른 인증 조합")),"cookie plus bearer combinations are retained conservatively");
      mode.set("public");var publicResult=CookieSessionProbe.analyze(api,config,profile,AUTH,normalized,()->false);
      verify(!publicResult.valid()&&!publicResult.complete(),"an anonymous response with the same identity blocks result application");
      for(String scenario:List.of("expired","login200","wrong","redirect","html","oversize","rotate")) {
        mode.set(scenario);int before=received.get();var result=CookieSessionProbe.analyze(api,config,profile,AUTH,normalized,()->false);
        verify(!result.valid(),scenario+" cannot authorize a session or cookie selection");
        if(scenario.equals("redirect"))verify(received.get()==before+1&&objects.get()==0,"login redirect is not followed to another endpoint");
      }
      mode.set("single");tamper.set(true);var changed=CookieSessionProbe.analyze(api,config,profile,AUTH,normalized,()->false);tamper.set(false);
      verify(!changed.valid()&&changed.summary().contains("실제 전송"),"Burp rewriting the submitted credentials invalidates the experiment");
      mode.set("ratelimit");int rateBefore=received.get();var limited=CookieSessionProbe.analyze(api,config,profile,AUTH,normalized,()->false);mode.set("single");
      verify(!limited.valid()&&!limited.complete()&&received.get()==rateBefore+1,"rate limiting stops experiments without retries");
      dropScope.set(true);int before=received.get();var excluded=CookieSessionProbe.analyze(api,config,profile,AUTH,normalized,()->false);dropScope.set(false);scope.add(origin+"/",true);
      verify(!excluded.valid()&&received.get()==before+1,"scope removal during a request stops all later experiments");
      before=received.get();var foreign=CookieSessionProbe.analyze(api,new CookieSessionProbe.Config("http://localhost:1/me","$.user.id","alice"),profile,AUTH,normalized,()->false);
      verify(!foreign.valid()&&received.get()==before,"cross-origin validation is rejected before transmission");
      before=received.get();var stopped=CookieSessionProbe.analyze(api,config,profile,AUTH,normalized,()->true);
      verify(!stopped.valid()&&received.get()==before,"cancellation is checked before the first send");
      networkError.set(true);var error=CookieSessionProbe.analyze(api,config,profile,AUTH,normalized,()->false);networkError.set(false);
      verify(!error.valid()&&!error.summary().contains("NETWORK_SECRET"),"transport errors cannot leak exception secrets into results");
      fails(()->CookieSessionProbe.cookies(Map.of("cookie","SID=one; SID=two")),"duplicate cookie names require recollection instead of ambiguous testing");
      fails(()->CookieSessionProbe.cookies(Map.of("cookie","SID=one\r\nInjected: yes")),"cookie control characters are rejected");
      fails(()->CookieSessionProbe.cookies(Map.of("cookie","SID=one,two")),"ambiguous cookie octets are rejected");
      fails(()->CookieSessionProbe.cookies(Map.of("cookie",String.join("; ",java.util.stream.IntStream.range(0,33).mapToObj(i->"c"+i+"=x").toList()))),"cookie inspection has a fixed input bound independent from traffic retention");
      verify(CookieSessionProbe.cookies(Map.of("cookie","SID=abc==; empty=")).get("SID").equals("abc=="),"base64 padding and empty cookie values survive parsing");
      fails(()->new CookieSessionProbe.Config(origin+"/me","$.id",""),"a user identity expectation is mandatory");
      fails(()->new CookieSessionProbe.Config("https://user:pass@example.test/me","$.id","1"),"validation URLs cannot embed basic-auth credentials");
      var rq=BountyFeaturesTest.rq(origin+"/object","GET","",List.of(header("Cookie","SID="+SECRET)));
      AtomicReference<AuthComparePanel> ref=new AtomicReference<>();edt(()->{
        var panel=new AuthComparePanel(api,()->List.of(AuthRecordTest.rr(rq,response(200,"{}"))));ref.set(panel);
        panel.assign(0,new AuthComparePanel.Candidate(1,"captured-profile",normalized,profile,1,""));
        var ui=panel.cookieSessions;ui.loadSlot();ui.url.setText(config.url());ui.expected.setText("alice");ui.start();
      });var panel=ref.get();panel.cookieSessions.pending.get(20,TimeUnit.SECONDS);AuthRecordTest.idle(panel);edt(()->{});
      edt(()->{
        var ui=panel.cookieSessions;verify(ui.apply.isEnabled()&&ui.cookieModel.getRowCount()==3,"modeless inspector presents cookie findings and an explicit apply action");
        verify(panel.sessionChecks[0]==null&&panel.slots[0].headers().equals(profile),"inspection alone never changes account credentials or enables comparison requests");
        ui.apply();verify(panel.sessionChecks[0]!=null&&panel.slots[0].headers().get("cookie").equals("SID="+SECRET),"explicit apply installs the verified subset and fresh identity gate");
        panel.compareSelection();
      });AuthRecordTest.idle(panel);
      verify(objects.get()==2,"successful preflight allows the requested comparison endpoint");
      mode.set("expired");edt(panel::compareSelection);AuthRecordTest.idle(panel);
      verify(objects.get()==3&&panel.runs.get(panel.runs.size()-1).baselineState==AuthComparePanel.Run.Baseline.INVALID,"expired session blocks the protected comparison request after a fresh check");
      networkError.set(true);edt(panel::compareSelection);AuthRecordTest.idle(panel);networkError.set(false);
      verify(objects.get()==3&&!panel.runs.get(panel.runs.size()-1).baselineNote.contains("NETWORK_SECRET"),"preflight transport exceptions cannot leak credentials into comparison history");
      mode.set("single");edt(()->{var ui=panel.cookieSessions;ui.loadSlot();ui.start();});panel.cookieSessions.pending.get(20,TimeUnit.SECONDS);AuthRecordTest.idle(panel);
      edt(()->{
        var ui=panel.cookieSessions;verify(ui.apply.isEnabled(),"a retained account can be inspected again");ui.expected.setText("mallory");verify(!ui.apply.isEnabled()&&ui.cookieModel.getRowCount()==0,"changing the identity condition invalidates stale results");
        fails(ui::apply,"stale UI results cannot replace credentials");
        panel.clearSlot(0);verify(panel.sessionChecks[0]==null,"clearing an account removes its validation configuration");panel.close();
      });edt(()->{});
      verify(panel.cookieSessions.expected.getText().isEmpty()&&panel.cookieSessions.report==null,"unload clears user identity inputs and cookie reports");
      verify(received.get()<200,"all scenarios use a bounded number of loopback requests");
    } finally {WorkbenchScope.release(api);server.stop(0);}
    System.out.println("CookieSessionProbeTest: "+checks+" checks passed");
  }
}
