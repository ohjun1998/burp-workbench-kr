package kr.workbench;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coverage/recheck evidence uses actual loopback responses; never treats discovery as a completed request. */
public final class ScopeInspectionCoverageTest extends WorkbenchTest {
  private static ScopeInspectionCoverage.Row row(ScopeInspectionRunner runner,String suffix,String account){
    return ScopeInspectionCoverage.rows(runner.snapshot()).stream().filter(r->r.url().endsWith(suffix)&&r.account().equals(account)&&r.method().equals("GET")).findFirst().orElseThrow();
  }
  private static void done(ScopeInspectionRunner runner)throws Exception{
    ScopeInspectionRunnerTest.await(()->ScopeInspectionRunnerTest.terminal(runner),"coverage terminal");
  }
  public static void main(String[] args)throws Exception{
    try(var fixture=new ScopeInspectionFixture()){
      var allowed=(java.util.function.Predicate<String>)url->url.equals(fixture.origin+"/app")||url.startsWith(fixture.origin+"/app/");
      var anonymous=ScopeInspectionRunnerTest.anonymous();
      try(var runner=new ScopeInspectionRunner(fixture.api,ScopeInspectionRunnerTest.options(fixture,"/app/api/documented",50,0,false,anonymous),allowed,s->{})){
        check(runner.snapshot().coverage().isEmpty(),"preparation has no manufactured execution trace");
        runner.start();done(runner);
        var trace=row(runner,"/app/api/documented","비로그인");
        check(trace.state().equals("ANALYZED")&&trace.status()==200,"actual complete JSON has analyzed coverage and observed status");
        check(trace.stages().equals(List.of("DISCOVERED","SCOPE_CHECKED","QUEUED","REQUESTED","RESPONDED","ANALYZED")),"coverage records each discovery-to-analysis phase");
        check(!trace.originalId().isBlank()&&runner.original(trace.originalId()).request().url().equals(trace.url()),"coverage original resolves to the exact actual account request");
        check(!trace.retryable(),"already analyzed route is not an unresolved recheck candidate");
        int before=fixture.visits.size();rejects(()->runner.recheck(List.of(trace.key())),"completed covered route cannot be blindly replayed");
        check(fixture.visits.size()==before,"recheck validation sends no requests");
      }
      fixture.visits.clear();
      try(var runner=new ScopeInspectionRunner(fixture.api,ScopeInspectionRunnerTest.options(fixture,"/app/",80,0,false,anonymous),allowed,s->{})){
        runner.start();done(runner);var selected=row(runner,"/app/config","비로그인");
        check(selected.state().equals("UNVISITED")&&!selected.stages().contains("REQUESTED")&&selected.originalId().isBlank(),"depth-limited discovered path has no invented transmission or response");
        check(selected.retryable()&&selected.reason().contains("깊이"),"safe depth-limited path explains why it is unvisited and explicitly recheckable");
        fixture.visits.clear();int sent=runner.snapshot().sent();
        check(runner.recheck(List.of(selected.key()))==1,"one selected unresolved route is accepted");done(runner);
        var inspected=row(runner,"/app/config","비로그인");
        check(inspected.state().equals("ANALYZED")&&!inspected.originalId().isBlank(),"selected recheck obtains an actual original and analysis result");
        check(fixture.visits.stream().allMatch(v->v.path().equals("/app/config")||v.path().contains("/wb-missing-")),"selective recheck sends only the chosen target and bounded common controls");
        check(runner.snapshot().sent()==sent+fixture.visits.size()&&runner.snapshot().sent()<=80,"recheck shares original request accounting and total budget");
        check(row(runner,"/app/private","비로그인").state().equals("UNVISITED"),"other unvisited paths are not silently replayed by selected recheck");
        check(!runner.snapshot().toString().contains(ScopeInspectionFixture.API_SECRET)&&!runner.snapshot().toString().contains("DB_PRIVATE_VALUE"),"coverage snapshots preserve product secret masking after recheck");
        check(ScopeInspectionCoverage.summary(runner.snapshot()).analyzed()>0&&ScopeInspectionCoverage.summary(runner.snapshot()).unconfirmed()>0,"summary keeps covered and unconfirmed denominators separate");
      }
      fixture.visits.clear();
      try(var runner=new ScopeInspectionRunner(fixture.api,ScopeInspectionRunnerTest.options(fixture,"/app/private",40,0,false,anonymous),allowed,s->{})){
        runner.start();done(runner);var denied=row(runner,"/app/private","비로그인");
        check(denied.state().equals("DENIED")&&denied.status()==403&&!denied.retryable(),"denied response remains unresolved permission evidence and cannot be retried as access success");
        int before=fixture.visits.size();rejects(()->runner.recheck(List.of(denied.key())),"denied route is excluded from selective retry");
        check(fixture.visits.size()==before,"denied recheck rejection has zero HTTP side effects");
      }
      fixture.visits.clear();AtomicBoolean scopeCurrent=new AtomicBoolean(true);
      try(var runner=new ScopeInspectionRunner(fixture.api,ScopeInspectionRunnerTest.options(fixture,"/app/",60,0,false,anonymous),url->scopeCurrent.get()&&allowed.test(url),s->{})){
        runner.start();done(runner);var unvisited=row(runner,"/app/config","비로그인");scopeCurrent.set(false);
        check(!row(runner,"/app/config","비로그인").retryable(),"live scope change immediately disables old coverage retry");
        int before=fixture.visits.size();rejects(()->runner.recheck(List.of(unvisited.key())),"stale scope cannot reuse saved recheck keys");check(fixture.visits.size()==before,"stale-scope rejection sends no HTTP");
      }
      fixture.visits.clear();fixture.rateLimited.set(true);
      var firstProfile=new ScopeInspection.Profile("alice","Alice",Map.of("Cookie",ScopeInspectionFixture.SESSION),null);
      try(var runner=new ScopeInspectionRunner(fixture.api,ScopeInspectionRunnerTest.options(fixture,"/app/rate",60,0,false,List.of(firstProfile)),allowed,s->{})){
        check(runner.profilesMatch(List.of(firstProfile))&&!runner.profilesMatch(List.of(firstProfile,firstProfile)),"current profile match validates the exact current account set without exposing headers");
        runner.start();ScopeInspectionRunnerTest.await(()->runner.snapshot().state()==ScopeInspection.State.PAUSED,"coverage rate pause");
        var previous=row(runner,"/app/rate","Alice");fixture.rateLimited.set(false);
        var refreshed=new ScopeInspection.Profile("alice","Alice",Map.of("Cookie",ScopeInspectionFixture.SESSION+"; marker=CHANGED"),null);
        runner.resume(List.of(refreshed));done(runner);
        check(runner.profilesMatch(List.of(refreshed))&&!runner.profilesMatch(List.of(firstProfile)),"current profile matching follows resumed revision instead of immutable initial options");
        var old=runner.snapshot().coverage().stream().filter(r->r.key().equals(previous.key())).findFirst().orElseThrow();
        check(old.state().equals("STALE")&&!old.retryable(),"credential refresh retains old-revision evidence without making it current or retryable");
        check(runner.snapshot().coverage().stream().anyMatch(r->r.url().endsWith("/app/rate")&&r.profileRevision().startsWith("r2:")&&r.state().equals("ANALYZED")),"new response receives its own current authentication-revision trace");
        int before=fixture.visits.size();rejects(()->runner.recheck(List.of(old.key())),"old-revision coverage key cannot replay under refreshed credentials");check(before==fixture.visits.size(),"stale revision rejection has no HTTP side effects");
      }
      fixture.visits.clear();
      try(var runner=new ScopeInspectionRunner(fixture.api,ScopeInspectionRunnerTest.options(fixture,"/app/",70,0,true,anonymous),allowed,s->{})){
        runner.start();runner.pause();ScopeInspectionRunnerTest.await(()->runner.snapshot().state()==ScopeInspection.State.PAUSED,"browser event paused");
        Map<String,Object> event=new HashMap<>(Map.of("runId",runner.id(),"kind","api","url",fixture.origin+"/app/api/documented","method","GET","requestId","a".repeat(64),"routeId","b".repeat(64),"networkStage","response","statusCode",200));
        runner.offerBrowser(event);event.put("networkStage","request");event.put("statusCode",0);runner.offerBrowser(event);
        var observed=row(runner,"/app/api/documented","browser-session");
        check(observed.state().equals("HTTP_RESPONDED")&&observed.status()==200,"late request metadata cannot downgrade already observed browser response");
        check(observed.originalId().isBlank()&&!observed.retryable()&&!observed.stages().contains("ANALYZED"),"browser status metadata does not invent response body analysis or HTTP-account originals");
        var waiting=new HashMap<String,Object>(event);waiting.put("requestId","d".repeat(64));waiting.put("url",fixture.origin+"/app/browser-pending");waiting.put("requiresQuery",true);waiting.put("networkStage","request");waiting.put("statusCode",0);runner.offerBrowser(waiting);
        var pending=row(runner,"/app/browser-pending","browser-session");
        check(pending.state().equals("REQUESTING")&&pending.status()==0&&!pending.stages().contains("RESPONDED"),"request without a completed response remains pending metadata rather than HTTP evidence");
        int rowsBefore=runner.snapshot().coverage().size(),findingsBefore=runner.snapshot().findings().size(),candidatesBefore=runner.snapshot().candidates().size(),respondedBefore=ScopeInspectionCoverage.summary(runner.snapshot()).responded();
        waiting.put("networkStage","response");
        for(Object invalid:List.of(0,-1,99,600,200.5,Double.NaN,Double.POSITIVE_INFINITY,"200")){
          waiting.put("statusCode",invalid);runner.offerBrowser(waiting);
          check(row(runner,"/app/browser-pending","browser-session").state().equals("REQUESTING"),"invalid or noninteger response status cannot promote pending browser coverage");
        }
        waiting.remove("statusCode");runner.offerBrowser(waiting);
        check(row(runner,"/app/browser-pending","browser-session").state().equals("REQUESTING"),"missing response status preserves pending coverage");
        waiting.put("requestId","e".repeat(64));waiting.put("url",fixture.origin+"/app/browser-phantom");waiting.put("statusCode",0);runner.offerBrowser(waiting);
        check(runner.snapshot().coverage().size()==rowsBefore&&runner.snapshot().findings().size()==findingsBefore&&runner.snapshot().candidates().size()==candidatesBefore
            &&ScopeInspectionCoverage.summary(runner.snapshot()).responded()==respondedBefore,"malformed completed events create no phantom rows, findings, candidates or responded counts");
        waiting.put("requestId","d".repeat(64));waiting.put("url",fixture.origin+"/app/browser-pending");waiting.put("statusCode",204);runner.offerBrowser(waiting);runner.offerBrowser(waiting);
        check(row(runner,"/app/browser-pending","browser-session").status()==204&&ScopeInspectionCoverage.summary(runner.snapshot()).responded()==respondedBefore+1
            &&runner.snapshot().coverage().size()==rowsBefore,"duplicate valid response events count one observed response for one request");
        int before=runner.snapshot().candidates().size();event.put("requestId","c".repeat(64));event.put("url",fixture.origin+"/app/browser-failed");event.put("networkStage","error");event.put("errorCode","NETWORK_ERROR");runner.offerBrowser(event);
        check(runner.snapshot().candidates().size()==before,"browser error event does not automatically schedule an HTTP retry");
        check(row(runner,"/app/browser-failed","browser-session").state().equals("FAILED"),"browser network failure remains explicit unconfirmed coverage");
        event.put("statusCode",200);runner.offerBrowser(event);runner.offerBrowser(event);var failed=row(runner,"/app/browser-failed","browser-session");
        check(failed.status()==0&&!failed.stages().contains("RESPONDED")&&ScopeInspectionCoverage.summary(runner.snapshot()).responded()==respondedBefore+1,"duplicate errors and stray error status never manufacture completed HTTP responses");
        runner.stop();
      }
    }
    var old=new ScopeInspection.Snapshot("preview",ScopeInspection.State.COMPLETED,"",0,0,0,0,0,0,List.of(),List.of(),List.of(new ScopeInspection.Candidate("https://example.test/api","GET","JS API","literal",true)),0,0);
    check(ScopeInspectionCoverage.rows(old).get(0).state().equals("UNVISITED")&&!ScopeInspectionCoverage.rows(old).get(0).retryable(),"legacy snapshots derive read-only discovery without fabricating HTTP evidence");
    check(ScopeInspectionCoverage.stateLabel("DENIED").contains("권한 미확인")&&ScopeInspectionCoverage.stageLabel("RESPONDED").equals("응답 수신"),"coverage labels distinguish transport evidence from authorization proof");
    System.out.println("ScopeInspectionCoverageTest: "+checks+" checks passed (production traces and selected read-only loopback rechecks)");
  }
}
