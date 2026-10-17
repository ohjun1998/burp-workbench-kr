package kr.workbench;

import burp.api.montoya.*;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import static kr.workbench.WorkbenchTest.*;

/** Real loopback transport plus immutable policy, semantic JSON and typed aggregate regressions. */
public final class AuthAccuracyWorkflowTest {
 static int checks;
 static void ok(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
 static void rejects(BountyFeaturesTest.Throwing action,String message){try{action.run();throw new AssertionError(message);}catch(AssertionError e){throw e;}catch(Exception expected){ok(true,message);}}
 public static void main(String[] args)throws Exception{
  BountyFeaturesTest.factory();
  var rules=new AuthJudgement.Rules(Set.of(),List.of(),"",AuthPolicy.Access.OWNER_ONLY,Set.of("$.data.id"),false);
  String a="{\"data\":{\"id\":10},\"time\":1}",b="{\"data\":{\"id\":10},\"time\":2}";
  ok(AuthJudgement.compare(200,a,200,b,rules,"").kind()==AuthFinding.Kind.SUSPECT,"important data survives changing metadata");
  ok(AuthJudgement.compare(200,a,200,"{\"time\":2}",rules,"").kind()==AuthFinding.Kind.INCOMPLETE,"missing important data is inconclusive");
  ok(AuthJudgement.compare(200,"<html>login</html>",200,"<html>login</html>",rules,"").kind()==AuthFinding.Kind.INCOMPLETE,"non JSON equality cannot bypass important field verification");
  rejects(()->new AuthJudgement.Rules(Set.of("$.data.id"),List.of(),"",AuthPolicy.Access.UNSPECIFIED,Set.of("$.data.id"),false),"important data cannot be excluded");
  rejects(()->new AuthJudgement.Rules(Set.of("owner"),List.of(),"$.owner"),"owner cannot be excluded");
  ok(AuthJudgement.match("$.roles[13].id","$.roles[*].id")&&!AuthJudgement.match("$.roles[x].id","$.roles[*].id"),"wildcard only matches array indices");
  var unordered=new AuthJudgement.Rules(Set.of(),List.of(),"",AuthPolicy.Access.UNSPECIFIED,Set.of(),true);
  ok(AuthJudgement.json("[1,2,1]",unordered).equals(AuthJudgement.json("[2,1,1]",unordered)),"optional array sorting preserves equivalent multisets");
  ok(!AuthJudgement.json("[1,2,1]",unordered).equals(AuthJudgement.json("[2,1]",unordered)),"array normalization preserves duplicate counts");
  ok(!AuthJudgement.json("[1,2]").equals(AuthJudgement.json("[2,1]")),"default retains array ordering");
  var oldMatrix=BountyMatrix.parse("GET /object ALLOW DENY UNKNOWN");
  ok(oldMatrix.expected("GET","http://x/object",3)==BountyMatrix.Expected.UNKNOWN,"legacy matrix leaves anonymous unspecified");
  var matrix=BountyMatrix.parse("GET /object ALLOW DENY UNKNOWN DENY");
  ok(matrix.expected("GET","http://x/object",3)==BountyMatrix.Expected.DENY,"fourth target has independent expectation");
  var typed=new AuthFinding(AuthFinding.Kind.BLOCKED,"우회 의심이라는 문구를 포함해도 코드로 처리","");
  ok(typed.verdict()==AuthVerdict.BLOCKED,"display wording cannot change stable verdict");
  AtomicInteger currentStatus=new AtomicInteger(200),received=new AtomicInteger(),writes=new AtomicInteger(),state=new AtomicInteger();
  AtomicBoolean publicIdentity=new AtomicBoolean(false),mutate=new AtomicBoolean(true);AtomicReference<String> role=new AtomicReference<>("admin"),tenant=new AtomicReference<>("A");
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",x->{received.incrementAndGet();String path=x.getRequestURI().getPath();int status=200;String body;
   if(path.equals("/me"))body="{\"id\":\"alice\",\"roles\":[\"viewer\",\""+role.get()+"\"],\"tenant\":\""+tenant.get()+"\"}";
   else if(x.getRequestMethod().equals("POST")){writes.incrementAndGet();if(mutate.get())state.set(1);body="{\"accepted\":true}";}
   else if(path.equals("/state"))body="{\"value\":"+state.get()+"}";
   else{status=currentStatus.get();body=status==200?"{\"data\":1}":"{\"error\":\"expired\"}";}
   if(path.equals("/me")&&publicIdentity.get()&&x.getRequestHeaders().getFirst("Cookie")==null)body="{\"id\":\"alice\",\"roles\":[\"viewer\"],\"tenant\":\"A\"}";
   if(path.equals("/me")&&!publicIdentity.get()&&x.getRequestHeaders().getFirst("Cookie")==null){status=401;body="{}";}
   byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);x.close();});server.start();
  String origin="http://127.0.0.1:"+server.getAddress().getPort();
  var client=java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
  var http=proxy(Http.class,(p,m,v)->{if(!m.getName().equals("sendRequest"))return def(m);HttpRequest q=(HttpRequest)v[0];var outgoing=java.net.http.HttpRequest.newBuilder(URI.create(q.url())).method(q.method(),q.method().equals("POST")?java.net.http.HttpRequest.BodyPublishers.ofString(q.bodyToString()):java.net.http.HttpRequest.BodyPublishers.noBody());for(var h:q.headers())if(!Set.of("host","content-length").contains(h.name().toLowerCase(Locale.ROOT)))outgoing.header(h.name(),h.value());var r=client.send(outgoing.build(),java.net.http.HttpResponse.BodyHandlers.ofString());return AuthRecordTest.rr(q,BountyFeaturesTest.rs(r.statusCode(),r.body(),List.of(header("Content-Type","application/json"))));});
  var api=proxy(MontoyaApi.class,(p,m,v)->m.getName().equals("http")?http:def(m));
  var scope=WorkbenchScope.install(api);scope.add(origin+"/",true);
  var original=BountyFeaturesTest.rq(origin+"/object","GET","",List.of(header("Cookie","SID=admin")));
  var captured=response(200,"{\"data\":0}");AtomicReference<AuthComparePanel> box=new AtomicReference<>();
  edt(()->{var panel=new AuthComparePanel(api,()->List.of(AuthRecordTest.rr(original,captured)));box.set(panel);panel.observe(BountyFeaturesTest.rq(origin+"/object","GET","",List.of(header("Cookie","SID=other"))),captured);panel.assign(0,panel.credentials.values().iterator().next());panel.anonymous(3);panel.matrixRules.setText("GET /object DENY UNKNOWN UNKNOWN DENY");});
  var panel=box.get();
  try{
   edt(panel::compareSelection);AuthRecordTest.idle(panel);var run=panel.runs.get(0);
   ok(run.currentBaseline!=null&&run.response==captured&&run.baseline()!=captured,"fresh reference retained separately from captured evidence");
   ok(run.baselineState==AuthComparePanel.Run.Baseline.VALID&&run.packets[3].response!=null,"fresh baseline precedes independent anonymous target");
   ok(AuthRunSummary.of(run).label().equals("우회 의심")&&panel.runs.size()==1,"one aggregate combines independent targets");
   int before=received.get();currentStatus.set(401);edt(panel::compareSelection);AuthRecordTest.idle(panel);var expired=panel.runs.get(1);
   ok(received.get()==before+1&&expired.packets[0].response==null&&expired.packets[3].response==null,"expired baseline aborts all account replays");
   ok(AuthRunSummary.of(expired).label().equals("세션 확인 필요")&&AuthRunSummary.of(expired).verdict()==AuthVerdict.REVIEW,"stale saved 200 never makes current 401 a green denial");
   run.note="완료";run.packets[0].response=response(401,"{}");run.packets[0].result(new AuthFinding(AuthFinding.Kind.REFERENCE,"","") );
   ok(AuthRunSummary.of(run).label().equals("세션 확인 필요"),"later reference expiry invalidates aggregate");
   edt(()->{panel.matrixEditor.load();ok(panel.matrixEditor.model.getColumnCount()==6,"policy table includes four targets");panel.matrixEditor.model.setValueAt("허용",0,5);ok(panel.matrixEditor.snapshot().expected("GET",origin+"/object",3)==BountyMatrix.Expected.ALLOW,"table edit changes explicit anonymous policy");});
   var config=new CookieSessionProbe.Config(origin+"/me","$.id","alice",List.of(new CookieSessionProbe.Claim("역할","$.roles[*]","admin"),new CookieSessionProbe.Claim("조직","$.tenant","A")));
   CookieSessionProbe.verify(api,config,Map.of("cookie","SID=admin"),AuthComparePanel.DEFAULT_AUTH,origin,()->false);ok(true,"server user role and tenant verified together");
   role.set("member");rejects(()->CookieSessionProbe.verify(api,config,Map.of("cookie","SID=admin"),AuthComparePanel.DEFAULT_AUTH,origin,()->false),"matching user with wrong role is rejected");
   role.set("admin");tenant.set("B");rejects(()->CookieSessionProbe.verify(api,config,Map.of("cookie","SID=admin"),AuthComparePanel.DEFAULT_AUTH,origin,()->false),"matching user with wrong tenant is rejected");
   tenant.set("A");publicIdentity.set(true);var report=CookieSessionProbe.analyze(api,config,Map.of("cookie","SID=admin"),AuthComparePanel.DEFAULT_AUTH,origin,()->false);ok(!report.valid(),"anonymous matching same user with a different role cannot prove session isolation");ok(report.trials().get(1).outcome()==CookieSessionProbe.Outcome.DIFFERENT&&report.trials().get(1).identityMatched(),"anonymous mismatch retains independent primary identity evidence");
   var effect=new AuthEffectCheck.Config(true,"POST","/write",origin+"/state","$.value","1",AuthEffectCheck.Mode.EQUALS,1);
   var write=BountyFeaturesTest.rq(origin+"/write","POST","{}",original.headers());var observer=AuthEffectCheck.observer(effect,write,AuthComparePanel.DEFAULT_AUTH);
   ok(observer.method().equals("GET")&&observer.headerValue("Cookie").equals("SID=admin"),"observer uses original owner credentials with GET");
   AuthEffectCheck.Reader reader=q->api.http().sendRequest(q).response();
   var prior=AuthEffectCheck.before(effect,observer,reader);api.http().sendRequest(write);var evidence=AuthEffectCheck.after(effect,prior,reader,()->false);
   ok(writes.get()==1&&evidence.outcome()==AuthEffectCheck.Outcome.OBSERVED,"real read write read observes one write and changed server state");
   ok(AuthEffectCheck.finding(evidence,BountyMatrix.Expected.DENY,typed).kind()==AuthFinding.Kind.SUSPECT,"state mutation can expose denied operation despite response wording");
   rejects(()->AuthEffectCheck.before(effect,observer,reader),"already expected state blocks another write");
   state.set(0);mutate.set(false);prior=AuthEffectCheck.before(effect,observer,reader);api.http().sendRequest(write);evidence=AuthEffectCheck.after(effect,prior,reader,()->false);
   ok(evidence.outcome()==AuthEffectCheck.Outcome.UNCHANGED&&AuthEffectCheck.finding(evidence,BountyMatrix.Expected.ALLOW,typed).kind()==AuthFinding.Kind.REVIEW,"HTTP 200 without actual mutation stays inconclusive");
   final var stable=prior;rejects(()->AuthEffectCheck.after(effect,stable,reader,()->true),"cancel prevents followup reads");
   rejects(()->AuthEffectCheck.observer(new AuthEffectCheck.Config(true,"POST","/write","http://other.test/state","$.value","1",AuthEffectCheck.Mode.EQUALS,1),write,AuthComparePanel.DEFAULT_AUTH),"observer rejects cross origin");
   rejects(()->new AuthEffectCheck.Config(true,"POST","/write",origin+"/state","$.value","1",AuthEffectCheck.Mode.EQUALS,4),"followup reads bounded to three");
   rejects(()->new AuthEffectCheck.Config(true,"POST","/write",origin+"/state","bad","",AuthEffectCheck.Mode.ABSENT,1),"absence path still requires valid JSON path");
   edt(()->panel.importantFields.setText("$.needed"));var incomplete=new AuthComparePanel.Run(89,original,response(200,"{\"data\":1}"),panel.context());ok(AuthComparison.assess(incomplete,0,original,response(200,"{\"data\":1}"),false).kind()==AuthFinding.Kind.INCOMPLETE,"expected DENY does not overwrite missing critical field evidence");edt(()->panel.importantFields.setText(""));
   var c=panel.context();var first=new AuthComparePanel.Run(90,original,response(200,"{\"id\":1,\"timestamp\":1}"),c);var second=new AuthComparePanel.Run(91,original,response(200,"{\"id\":1,\"timestamp\":2}"),c);
   ok(AuthDynamicFields.suggest(List.of(first,second)).equals(List.of("$.timestamp")),"dynamic suggestions compare same operation and same identity");
   ok(panel.ignoredFields.getText().isBlank(),"dynamic suggestions never silently exclude fields");
   var other=new AuthComparePanel.Run(92,BountyFeaturesTest.rq(origin+"/object","GET","",List.of(header("Cookie","SID=different"))),response(200,"{\"id\":1,\"timestamp\":3}"),c);
   ok(AuthDynamicFields.suggest(List.of(first,other)).isEmpty(),"different identity never supplies dynamic exclusion evidence");
   ok(evidence.bytes()>0,"effect evidence participates in retained byte accounting");
   state.set(0);mutate.set(true);AtomicReference<AuthComparePanel> writeBox=new AtomicReference<>();
   edt(()->{var w=new AuthComparePanel(api,()->List.of(AuthRecordTest.rr(write,response(200,"{\"accepted\":true}"))));writeBox.set(w);w.observe(BountyFeaturesTest.rq(origin+"/object","GET","",List.of(header("Cookie","SID=other"))),captured);w.assign(0,w.credentials.values().iterator().next());w.writes.setSelected(true);w.matrixRules.setText("POST /write DENY UNKNOWN UNKNOWN UNKNOWN");var settings=w.effectSettings;settings.enabled.setSelected(true);settings.method.setSelectedItem("POST");settings.path.setText("/write");settings.url.setText(origin+"/state");settings.jsonPath.setText("$.value");settings.expected.setText("1");w.compareSelection();});
   var w=writeBox.get();try{AuthRecordTest.idle(w);var actual=w.runs.get(0);ok(actual.packets[0].effect!=null&&actual.packets[0].effect.outcome()==AuthEffectCheck.Outcome.OBSERVED&&AuthRunSummary.of(actual).verdict()==AuthVerdict.SUSPECT,"production controller records denied write state mutation in one aggregate");ok(writes.get()==3,"production controller does not replay original write for baseline");state.set(0);mutate.set(false);edt(w::compareSelection);AuthRecordTest.idle(w);ok(AuthRunSummary.of(w.runs.get(1)).verdict()==AuthVerdict.REVIEW,"production controller holds HTTP 200 with no observed state change");}finally{edt(w::close);}
   scope.clear();rejects(()->panel.sendRead(observer,c),"observer rechecks current Scope before transport");scope.add(origin+"/",true);

   ok(received.get()<50&&writes.get()==4,"loopback scenarios remain bounded with no external targets");
  }finally{edt(panel::close);WorkbenchScope.release(api);server.stop(0);}
  System.out.println("AuthAccuracyWorkflowTest: "+checks+" checks passed");
 }
}
