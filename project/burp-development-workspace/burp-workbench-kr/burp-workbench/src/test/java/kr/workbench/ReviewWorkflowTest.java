package kr.workbench;
import burp.api.montoya.*;import burp.api.montoya.internal.*;import burp.api.montoya.http.*;import burp.api.montoya.http.message.*;import burp.api.montoya.http.message.requests.*;import burp.api.montoya.repeater.*;
import javax.swing.*;import java.util.*;import java.util.List;import java.util.concurrent.atomic.*;

public final class ReviewWorkflowTest extends WorkbenchTest {
 static JsAnalysis.Endpoint item(String url,String method,String query,String body,boolean complete){return new JsAnalysis.Endpoint(url,method,"fetch",query,"Authorization",body,3,0,10,complete,"");}
 static HttpRequest draftRequest(String url,String method,String body,List<HttpHeader> headers){HttpRequest base=request(url,method,url.startsWith("https://auth.test/")||url.startsWith("https://allowed.test/"),headers);return proxy(HttpRequest.class,(p,m,a)->switch(m.getName()){
  case "withMethod"->draftRequest(url,(String)a[0],body,headers);case "withBody"->draftRequest(url,method,(String)a[0],headers);
  case "withHeader"->{List<HttpHeader> hs=new ArrayList<>(headers);hs.add(header((String)a[0],(String)a[1]));yield draftRequest(url,method,body,hs);}
  case "bodyToString"->body;case "body"->bytes(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));default->m.invoke(base,a);});}
 static Object getUnchecked(Object o,String name){try{return get(o,name);}catch(Exception e){throw new RuntimeException(e);}}
 static void throwsRule(Runnable r,String message){try{r.run();throw new AssertionError(message);}catch(IllegalArgumentException e){check(true,message);}}
 public static void main(String[] args)throws Exception{
  var rawRules=AuthJudgement.rules("$.requestId, timestamp","success=false\nerror=FORBIDDEN","$.ownerId");
  var rules=new AuthJudgement.Rules(rawRules.ignored(),rawRules.denied(),rawRules.ownerPath(),AuthPolicy.Access.OWNER_ONLY);
  String a="{\"ownerId\":\"A\",\"orderId\":123,\"requestId\":\"aaa\"}",b="{\"requestId\":\"bbb\",\"orderId\":123,\"ownerId\":\"A\"}";
  var r=AuthJudgement.compare(200,a,200,b,rules,"B");check(r.state().equals("타인 객체 접근 의심"),"owner mapping detects foreign-object candidate");check(!r.changes().contains("requestId"),"ignored field removed from comparison details only");
  check(AuthJudgement.compare(200,a,200,b,rules,"").state().equals("우회 의심"),"normalized JSON equality despite field order and ignored request ID");
  check(AuthJudgement.compare(200,a,200,"{\"success\":false}",rules,"B").state().equals("차단 근거 확인"),"HTTP 200 logical denial recognized");
  check(AuthJudgement.compare(200,"{\"success\":false}",200,"{\"success\":false}",rules,"").state().equals("판정 보류"),"denied baseline does not create bypass candidate");
  check(AuthJudgement.compare(200,a,403,"",rules,"B").state().equals("차단 근거 확인"),"HTTP denial reason retained");
  check(AuthJudgement.compare(401,a,200,a,rules,"").state().equals("판정 보류"),"invalid baseline cannot establish authorization");
  check(AuthJudgement.compare(200,a,302,"",rules,"").state().equals("판정 보류"),"redirect not treated as enforcement proof");
  check(AuthJudgement.compare(200,"{\"requestId\":1}",200,"{\"requestId\":2}",rules,"").state().equals("판정 보류"),"all JSON fields excluded cannot establish equality");
  check(AuthJudgement.compare(200,"<html>A</html>",200,"<html>B</html>",rules,"").state().equals("판정 보류"),"non JSON responses keep manual review");
  check(AuthJudgement.compare(200,"{bad}",200,"{bad}",rules,"").state().equals("우회 의심"),"same non JSON success remains an unconfirmed candidate");
  check(AuthJudgement.compare(200,"{\"ownerId\":123}",200,"{\"ownerId\":123}",rules,"123").state().equals("우회 의심"),"numeric owner text normalized without false foreign-owner claim");
  check(!AuthJudgement.json("{\"a.b\":1}").equals(AuthJudgement.json("{\"a\":{\"b\":1}}")),"literal dotted keys cannot collide with nested JSON paths");
  check(!AuthJudgement.json("{\"x\":{}}").equals(AuthJudgement.json("{\"x\":\"{}\"}")),"empty JSON container distinct from string");
  check(AuthJudgement.compare(200,"{\"x\":null}",200,"{}",rules,"").changes().contains("[없음]"),"missing field differs from explicit null");
  check(AuthJudgement.compare(200,"{\"items\":[1,2]}",200,"{\"items\":[1,3]}",rules,"").changes().contains("$.items[1]"),"array differences include index path");
  check(!AuthJudgement.json("{\"id\":9007199254740992}").equals(AuthJudgement.json("{\"id\":9007199254740993}")),"large object IDs compared without floating point precision loss");
  check(AuthJudgement.compare(200,"",200,"",rules,"").state().equals("판정 보류"),"empty successful responses do not imply bypass");
  check(AuthJudgement.compare(200,"{\"ownerId\":1e100000000}",200,"{\"ownerId\":1e100000000}",rules,"B").state().equals("타인 객체 접근 의심"),"large exponent comparison does not expand huge decimal text");
  throwsRule(()->AuthJudgement.rules("","not-a-rule",""),"invalid denial syntax rejected before requests");
  throwsRule(()->AuthJudgement.rules("x".repeat(3000),"",""),"oversized rule rejected");
  var draft=JsRequestDraft.create(item("https://allowed.test/refund?sid=SECRET","POST","orderId, token","orderId, amount",true),null);
  check(draft.method().equals("POST")&&draft.body().contains("\"amount\":\"REPLACE_ME\""),"JS draft preserves method and body names");
  check(!draft.url().contains("SECRET")&&draft.url().contains("token=REPLACE_ME"),"draft query uses names only, never captured values");
  throwsRule(()->JsRequestDraft.create(item("https://allowed.test/api/{dynamic}","GET","","",false),null),"unresolved dynamic endpoint blocked");
  throwsRule(()->JsRequestDraft.create(item("https://allowed.test/api","?","","",true),null),"unknown method cannot silently become GET");
  throwsRule(()->JsRequestDraft.create(item("https://user:pass@allowed.test/api","GET","","",true),null),"embedded credentials rejected");
  check(JsRequestDraft.create(item("https://allowed.test/api/{dynamic}","GET","","",false),"https://allowed.test/api/123").url().endsWith("/123"),"manual dynamic resolution supported");
  var t=new ActivityTracker();t.discover(new ReconMetadata.Discovery("https://allowed.test/refund","POST",Set.of(),Set.of("orderId"),"JS 분석"));t.evidence("POST","https://allowed.test/refund","https://allowed.test/app.js?token=SECRET",3,"fetch");
  check(t.views().get(0).state().equals("발견만 함")&&t.views().get(0).observed()==0,"discovery distinct from request observation");
  String saved=t.save();check(!saved.contains("SECRET")&&saved.contains("app.js"),"persisted evidence strips query credentials");ActivityTracker imported=new ActivityTracker();imported.load(saved);check(imported.views().equals(t.views()),"version 2 evidence metadata round trip");
  ActivityTracker legacy=new ActivityTracker();legacy.load("{\"version\":1,\"endpoints\":[{\"url\":\"https://allowed.test/old\",\"method\":\"GET\",\"observed\":2,\"completed\":true}]}");check(legacy.views().get(0).state().equals("점검 완료"),"version 1 imports remain supported");
  t.observe(request("https://allowed.test/refund","POST",true,List.of()),"PROXY");check(t.views().get(0).state().equals("요청 관측"),"observed state changes only on request");t.complete(t.views().get(0).key(),true);t.review("POST","https://allowed.test/refund","사용자 B: 우회 의심");check(t.views().get(0).state().equals("재검토 필요"),"authorization finding reopens completed endpoint for review");t.complete(t.views().get(0).key(),true);check(t.views().get(0).review().isEmpty(),"explicit completion clears review reason");
  AtomicInteger repeated=new AtomicInteger(),sent=new AtomicInteger();AtomicReference<HttpRequest> last=new AtomicReference<>();
  var repeater=proxy(Repeater.class,(p,m,aa)->{if(m.getName().equals("sendToRepeater")){repeated.incrementAndGet();last.set((HttpRequest)aa[0]);}return def(m);});
  var http=proxy(Http.class,(p,m,aa)->{if(m.getName().equals("sendRequest")){sent.incrementAndGet();return AuthRecordTest.rr((HttpRequest)aa[0],response(200,b));}return def(m);});
  var api=proxy(MontoyaApi.class,(p,m,aa)->switch(m.getName()){case "http"->http;case "repeater"->repeater;case "scope"->proxy(burp.api.montoya.scope.Scope.class,(pp,mm,aaa)->mm.getName().equals("isInScope")?aaa[0].toString().startsWith("https://allowed.test/")||aaa[0].toString().startsWith("https://auth.test/"):def(mm));default->def(m);});
  ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,aa)->{if(m.getName().equals("requestOptions"))return proxy(RequestOptions.class,(pp,mm,aaa)->pp);if(m.getReturnType()==HttpRequest.class&&aa.length==1&&aa[0] instanceof String url)return draftRequest(url,"GET","",List.of());return def(m);});
  AtomicReference<JsAnalysisPanel> jbox=new AtomicReference<>();AtomicReference<ActivityPanel> abox=new AtomicReference<>();edt(()->{abox.set(new ActivityPanel(api));jbox.set(new JsAnalysisPanel(api,()->{}));});var js=jbox.get();var activity=abox.get();edt(()->{js.coverage=activity::analyzed;js.observation=activity::observation;js.load("fetch('/refund',{method:'POST',body:{orderId:123}});","https://allowed.test/main.js");});JsAnalysisPanelTest.waitDone(js);for(int i=0;i<100&&activity.tracker.views().isEmpty();i++)Thread.sleep(10);
  edt(()->{js.urlTable.setRowSelectionInterval(0,0);js.toRepeater.doClick();});check(last.get().method().equals("POST")&&last.get().bodyToString().contains("orderId"),"UI Repeater draft preserves POST and JSON body keys");check(sent.get()==0&&activity.tracker.views().get(0).observed()==0,"preparing Repeater never sends or records a request");
  edt(()->js.offer(request("https://allowed.test/background.js","GET",true,List.of()),response(200,"fetch('/background');")));JsAnalysisPanelTest.waitDone(js);for(int i=0;i<100&&activity.tracker.views().size()<2;i++)Thread.sleep(10);
  check(activity.tracker.views().stream().anyMatch(v->v.url().endsWith("/background")),"background undisplayed source immediately reaches activity tracker");check(js.sourceUrl.getText().endsWith("main.js"),"background collection preserves current JS selection");
  var restored=new JsMiner.Recovered("https://allowed.test/main.js.map","src/private.js","// first line\nfetch('/from-map');");var originalResult=(JsAnalysis.Result)get(js,"current");var m=originalResult.miner();edt(()->js.applyMiner("https://allowed.test/main.js",originalResult,new JsMiner.Result(m.domains(),m.clouds(),m.dependencies(),m.maps(),List.of(restored),m.note()),((java.util.concurrent.atomic.AtomicLong)getUnchecked(js,"epoch")).get()));edt(()->{});for(int i=0;i<100&&activity.tracker.views().size()<3;i++)Thread.sleep(10);
  check(activity.tracker.views().stream().anyMatch(v->v.url().endsWith("/from-map")&&v.evidence().stream().anyMatch(ev->ev.kind().equals("source map: src/private.js"))),"recovered source-map endpoints immediately reach activity with file evidence");
  edt(()->js.navigateSource("https://allowed.test/main.js",2,"source map: src/private.js"));check(js.input.getText().equals(restored.content())&&js.input.getCaretPosition()==14,"source-map evidence opens correct recovered file and line");

  edt(()->{activity.refresh();activity.filter.setSelectedIndex(5);});check(activity.table.getRowCount()==3,"JS unobserved filter includes both sources");activity.tracker.observe(request("https://allowed.test/background","GET",true,List.of()),"PROXY");edt(activity::refresh);check(activity.table.getRowCount()==2,"observed request leaves JS-unobserved list");
  AtomicReference<AuthComparePanel> pbox=new AtomicReference<>();var original=AuthRecordTest.q("ADMIN");edt(()->pbox.set(new AuthComparePanel(api,()->List.of(AuthRecordTest.rr(original,response(200,a))))));var auth=pbox.get();edt(()->{auth.observe(AuthRecordTest.q("B"),response(200,b));auth.assign(0,auth.credentials.values().iterator().next());auth.profileNames[0].setText("사용자 B");auth.profileOwners[0].setText("B");auth.accessPolicy.setSelectedIndex(2);auth.ownerPath.setText("$.ownerId");auth.ignoredFields.setText("$.requestId");auth.reviewSink=activity::review;auth.compareSelection();auth.profileNames[0].setText("변경된 이름");});AuthRecordTest.idle(auth);
  check(auth.historyModel.getValueAt(0,2).toString().contains("사용자 B")&&auth.runs.get(0).packets[0].note.contains("타인 객체 접근 의심"),"per-account result retains label/rules snapshot");check(auth.judgementDetails.getText().contains("사용자 B")&&!auth.judgementDetails.getText().contains("requestId :"),"JSON reasons visible with ignored fields excluded");
  for(int i=0;i<100&&activity.tracker.views().stream().noneMatch(v->v.review().contains("사용자 B"));i++)Thread.sleep(10);check(activity.tracker.views().stream().anyMatch(v->v.review().contains("사용자 B")),"auth suspicious result linked to activity review queue");
  edt(()->{js.resolveWindow.opener=(owner,title,content,modal,closed)->{check(!modal,"dynamic target dialog modeless");return null;};js.resolveWindow.open();auth.comparisonSettings.opener=(owner,title,content,modal,closed)->{check(!modal,"comparison settings dialog modeless");return null;};auth.comparisonSettings.open();js.close();auth.close();activity.close();});edt(()->{});check(js.resolving==null&&activity.worker.isShutdown(),"unload clears dynamic draft and closes tracking workers");
  System.out.println("ReviewWorkflowTest: "+checks+" checks passed");
 }
}
