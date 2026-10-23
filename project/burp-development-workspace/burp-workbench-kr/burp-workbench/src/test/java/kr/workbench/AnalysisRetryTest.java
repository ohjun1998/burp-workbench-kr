package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scope.Scope;
import burp.api.montoya.proxy.Proxy;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import burp.api.montoya.core.Registration;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Selected retries must keep identity, bounds and scope without any transport. */
public final class AnalysisRetryTest extends WorkbenchTest {
  static MontoyaApi api(AtomicBoolean allowed,AtomicInteger network){
    var scope=proxy(Scope.class,(p,m,a)->m.getName().equals("isInScope")?allowed.get():def(m));
    return proxy(MontoyaApi.class,(p,m,a)->{if(m.getName().equals("scope"))return scope;if(m.getName().equals("http")){network.incrementAndGet();throw new AssertionError("retry attempted network access");}return def(m);});
  }
  static void drained(ThreadPoolExecutor worker)throws Exception{
    FutureTask<Void> barrier=new FutureTask<>(()->null);
    if(!worker.getQueue().offer(barrier,5,TimeUnit.SECONDS))throw new AssertionError("worker barrier queue timeout");
    barrier.get(10,TimeUnit.SECONDS);edt(()->{});
  }
  public static void main(String[] args)throws Exception{
    installTrafficPacketFactory();
    pollingWithoutDuplicateLiveHandler();
    AtomicBoolean allowed=new AtomicBoolean(true);AtomicInteger network=new AtomicInteger();var api=api(allowed,network);
    AtomicReference<JsAnalysisPanel> jsRef=new AtomicReference<>();AtomicReference<ResponseReconPanel> responseRef=new AtomicReference<>();
    edt(()->{jsRef.set(new JsAnalysisPanel(api,()->{}));responseRef.set(new ResponseReconPanel(api));});
    var js=jsRef.get();var responsePanel=responseRef.get();var jsWorker=(ThreadPoolExecutor)get(js,"worker");
    try(DiskTrafficStore store=new DiskTrafficStore()){
      var request=request("https://retry.test/same.js?token=DO_NOT_DISPLAY","GET",true,List.of(header("Cookie","session=first")));
      var first=response(200,"fetch('/first-original');");var second=response(200,"fetch('/second-original');");
      var firstRef=store.append(request,first);var secondRef=store.append(request,second);
      var ledger=new AnalysisRetries();String firstId=ledger.record(firstRef,"GET",request.url(),AnalysisRetries.State.FAILED,"error");String secondId=ledger.record(secondRef,"GET",request.url(),AnalysisRetries.State.FAILED,"error");
      check(!firstId.equals(secondId)&&ledger.snapshot().size()==2,"same URL responses retain distinct original identities");
      check(ledger.snapshot().stream().noneMatch(e->e.url().contains("DO_NOT_DISPLAY")),"retry table metadata removes query values");
      check(ledger.begin(firstId)!=null&&ledger.begin(firstId)==null,"an already queued selected retry cannot be submitted twice");
      for(int i=0;i<300;i++)ledger.record(null,"GET","https://retry.test/"+i,AnalysisRetries.State.QUEUE_FULL,"queue");
      check(ledger.snapshot().size()==AnalysisRetries.LIMIT&&ledger.get(firstId)==null,"retry metadata has a fixed cap and releases evicted original references");
      var unlinked=ledger.snapshot().get(0);check(!unlinked.retryable(),"unlinked metadata never falls back to URL-based replay");
      CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
      jsWorker.execute(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
      check(entered.await(5,TimeUnit.SECONDS),"JS worker paused before deterministic queue overflow");
      for(int i=0;i<100;i++)jsWorker.getQueue().add(()->{});
      check(!js.offer(request,first,firstRef)&&js.retries.get(firstRef.id()).state()==AnalysisRetries.State.QUEUE_FULL,"JS queue exclusion is linked to the exact retained original");
      jsWorker.getQueue().clear();release.countDown();drained(jsWorker);
      edt(()->js.retryOriginal(firstRef.id()));drained(jsWorker);
      check(js.retries.get(firstRef.id()).state()==AnalysisRetries.State.COMPLETE,"selected JS original finishes local retry after queue pressure clears");
      check(js.maskedReport().contains("/first-original")&&!js.maskedReport().contains("/second-original"),"JS retry restores the selected older response, not latest same-URL content");
      long processedBeforeReuse=js.processed.get();
      var reusedRef=store.append(request,first);
      js.offer(request,first,reusedRef);drained(jsWorker);
      check(js.retries.get(reusedRef.id()).state()==AnalysisRetries.State.REUSED&&js.processed.get()==processedBeforeReuse,"same-content original points to existing results without claiming a new complete analysis");
      var brokenRequest=request("https://retry.test/broken.js","GET",true,List.of());
      var rawBroken=response(200,"fetch('/broken-encoding');");
      HttpResponse broken=proxy(HttpResponse.class,(p,m,a)->m.getName().equals("headerValue")?("Content-Encoding".equals(a[0])?"unsupported-encoding":"application/javascript"):m.invoke(rawBroken,a));
      registerTrafficPacket(brokenRequest,broken);var brokenRef=store.append(brokenRequest,broken);
      js.offer(brokenRequest,broken,brokenRef);drained(jsWorker);
      check(js.retries.get(brokenRef.id()).state()==AnalysisRetries.State.FAILED&&js.retries.get(brokenRef.id()).retryable(),"decoding failure is FAILED and actionable rather than REUSED or PARTIAL");
      String scopeId=js.retries.record(secondRef,"GET",request.url(),AnalysisRetries.State.FAILED,"fixture failure");allowed.set(false);
      edt(()->js.retryOriginal(scopeId));drained(jsWorker);
      check(js.retries.get(scopeId).state()==AnalysisRetries.State.SCOPE_EXCLUDED,"retry rechecks current scope before reading or analysis");allowed.set(true);
      var tooLarge=response(200,"x".repeat(2*1024*1024+1));var largeRef=store.append(request,tooLarge);
      responsePanel.offer(request,tooLarge,largeRef);drained(responsePanel.worker);
      check(responsePanel.retries.get(largeRef.id()).state()==AnalysisRetries.State.TOO_LARGE&&!responsePanel.retries.get(largeRef.id()).retryable(),"response oversize remains explicit and cannot bypass its body limit through retry");
      CountDownLatch responseEntered=new CountDownLatch(1),responseRelease=new CountDownLatch(1);
      responsePanel.worker.execute(()->{responseEntered.countDown();try{responseRelease.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
      check(responseEntered.await(5,TimeUnit.SECONDS),"response worker paused before deterministic queue overflow");
      for(int i=0;i<50;i++)responsePanel.worker.getQueue().add(()->{});
      responsePanel.offer(request,first,firstRef);
      check(responsePanel.retries.get(firstRef.id()).state()==AnalysisRetries.State.QUEUE_FULL,"response queue exclusion is retained as actionable metadata");
      responsePanel.worker.getQueue().clear();responseRelease.countDown();drained(responsePanel.worker);
      edt(()->responsePanel.retryOriginal(firstRef.id()));drained(responsePanel.worker);
      check(responsePanel.retries.get(firstRef.id()).state()==AnalysisRetries.State.COMPLETE,"response selected retry processes only the stored packet");
      var malformedRequest=request("https://retry.test/malformed-json","GET",true,List.of());
      var malformedResponse=response(200,"{\"password\":");var malformedRef=store.append(malformedRequest,malformedResponse);
      responsePanel.offer(malformedRequest,malformedResponse,malformedRef);drained(responsePanel.worker);
      check(responsePanel.retries.get(malformedRef.id()).state()==AnalysisRetries.State.PARTIAL&&responsePanel.retries.get(malformedRef.id()).note().contains("JSON 구조"),"small malformed JSON retains the analyzer's typed incomplete state and reason");
      var limitedRequest=request("https://retry.test/many-errors","GET",true,List.of());
      String limitedBody=java.util.stream.IntStream.range(10000,10040).mapToObj(i->"ORA-"+i).collect(java.util.stream.Collectors.joining(" "));
      var limitedResponse=response(500,limitedBody);var limitedRef=store.append(limitedRequest,limitedResponse);
      responsePanel.offer(limitedRequest,limitedResponse,limitedRef);drained(responsePanel.worker);
      check(responsePanel.retries.get(limitedRef.id()).state()==AnalysisRetries.State.PARTIAL&&responsePanel.retries.get(limitedRef.id()).note().contains("32개 제한"),"small response reaching the finding cap remains PARTIAL with the actual analyzer limit");
      var partialRequest=request("https://retry.test/partial","GET",true,List.of());
      var partialResponse=response(500,"BadSqlGrammarException "+" ".repeat(PassiveFindings.BODY_LIMIT));
      var partialRef=store.append(partialRequest,partialResponse);
      responsePanel.offer(partialRequest,partialResponse,partialRef);drained(responsePanel.worker);
      check(responsePanel.retries.get(partialRef.id()).state()==AnalysisRetries.State.PARTIAL&&responsePanel.retries.get(partialRef.id()).retryable(),"partially inspected response remains explicit and offers a bounded selected retry");
      var beforeCounts=java.util.Map.copyOf(responsePanel.counts);
      check(responsePanel.issues.values().stream().anyMatch(f->f.url().endsWith("/partial")&&f.rule().equals("오류 구현 정보 노출 후보")),"partial-count fixture contains an actual grouped observation");
      edt(()->responsePanel.retryOriginal(partialRef.id()));drained(responsePanel.worker);
      check(responsePanel.counts.equals(beforeCounts)&&responsePanel.retries.get(partialRef.id()).state()==AnalysisRetries.State.PARTIAL,"retrying the same partial original preserves observation counts and analysis limits");
      var anotherPartialRef=store.append(partialRequest,partialResponse);
      responsePanel.offer(partialRequest,partialResponse,anotherPartialRef);drained(responsePanel.worker);
      check(beforeCounts.entrySet().stream().anyMatch(e->responsePanel.counts.getOrDefault(e.getKey(),0)>e.getValue()),"a distinct captured original still increments observation counts");
      lateCompletionKeepsNewRetry(api,store);
      DiskTrafficStore expired=new DiskTrafficStore();var expiredRef=expired.append(request,first);
      String expiredId=responsePanel.retries.record(expiredRef,"GET",request.url(),AnalysisRetries.State.FAILED,"error");expired.close();
      edt(()->responsePanel.retryOriginal(expiredId));drained(responsePanel.worker);
      check(responsePanel.retries.get(expiredId).state()==AnalysisRetries.State.EXPIRED,"expired original is reported without fetching a replacement");
      check(network.get()==0,"all selected retries perform zero HTTP transport calls");
      edt(()->{js.retries.content(js::retryOriginal);check(js.progress.getToolTipText().contains("JVM 전체 heap")&&js.progress.getToolTipText().contains("캐시 추정"),"runtime tooltip separates JVM heap from estimated cache retention");});
    }finally{edt(()->{js.close();responsePanel.close();});}
    System.out.println("AnalysisRetryTest: "+checks+" checks passed");
  }
  static void pollingWithoutDuplicateLiveHandler()throws Exception{
    AtomicInteger registrations=new AtomicInteger(),received=new AtomicInteger(),deregistered=new AtomicInteger();CountDownLatch observed=new CountDownLatch(1);
    var request=request("https://retry.test/history.js","GET",true,List.of());var response=response(200,"fetch('/history');");
    var entry=proxy(ProxyHttpRequestResponse.class,(p,m,a)->switch(m.getName()){case "id"->1;case "request"->request;case "response"->response;default->def(m);});
    var proxy=proxy(Proxy.class,(p,m,a)->switch(m.getName()){
      case "history"->List.of(entry);
      case "registerResponseHandler"->{registrations.incrementAndGet();yield proxy(Registration.class,(rp,rm,ra)->{if(rm.getName().equals("deregister"))deregistered.incrementAndGet();return def(rm);});}
      default->def(m);
    });
    try(var monitor=new JsHistoryMonitor(proxy,(q,r)->{received.incrementAndGet();observed.countDown();return true;},()->true,false)){
      monitor.start();check(observed.await(5,TimeUnit.SECONDS),"integrated history mode still polls and backfills recorded responses");
      check(registrations.get()==0&&received.get()>0,"integrated history mode does not register an additional Proxy live handler");
    }
    check(deregistered.get()==0,"poll-only shutdown leaves the capture owner's live handler untouched");
    try(var standalone=new JsHistoryMonitor(proxy,(q,r)->true,()->true)){
      standalone.start();check(registrations.get()==1,"default standalone history mode preserves its live listener");
    }
    check(deregistered.get()==1,"standalone history mode unregisters its own live handler");
  }
  static void lateCompletionKeepsNewRetry(MontoyaApi api,DiskTrafficStore store)throws Exception{
    AtomicReference<JsAnalysisPanel> box=new AtomicReference<>();edt(()->box.set(new JsAnalysisPanel(api,()->{})));
    JsAnalysisPanel panel=box.get();ThreadPoolExecutor worker=(ThreadPoolExecutor)get(panel,"worker");
    CountDownLatch nextEntered=new CountDownLatch(1),releaseNext=new CountDownLatch(1);
    AtomicBoolean swapped=new AtomicBoolean();
    var request=request("https://retry.test/epoch.js","GET",true,List.of());var response=response(200,"fetch('/epoch-proof');");var original=store.append(request,response);
    try{
      // The real publication hook runs after the old worker's token check, before it returns.
      panel.coverage=(result,source)->{
        if(!swapped.compareAndSet(false,true))return;
        panel.passive.setSelected(false);panel.passive.setSelected(true);
        worker.execute(()->{nextEntered.countDown();try{releaseNext.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        panel.retryOriginal(original.id());
      };
      panel.offer(request,response,original);
      check(nextEntered.await(10,TimeUnit.SECONDS),"old JS publication finishes before the new retry queue is released");
      check(panel.retries.get(original.id()).state()==AnalysisRetries.State.QUEUED,"late completion from the old epoch cannot overwrite the new selected retry state");
      releaseNext.countDown();drained(worker);
      check(panel.retries.get(original.id()).state()==AnalysisRetries.State.COMPLETE,"new selected retry completes after the old epoch retires");
    }finally{releaseNext.countDown();edt(panel::close);}
  }
}
