package kr.workbench;

import burp.api.montoya.http.handler.HttpResponseReceived;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/** Production capture gates against a Montoya adapter; no Burp or network is claimed. */
public final class CaptureProvenanceTest extends WorkbenchTest {
  static long metric(WorkbenchExtension ext,String field)throws Exception{return ((AtomicLong)get(ext,field)).get();}
  static Object invoke(WorkbenchExtension ext,String name)throws Exception{
    Method method=WorkbenchExtension.class.getDeclaredMethod(name);method.setAccessible(true);return method.invoke(ext);
  }
  public static void main(String[] args)throws Exception{
    try(var fixture=new TrafficScopeModesTest.Fixture()){
      var ext=fixture.ext;
      fixture.send("https://burp.test/api/private?token=NEVER_RETAIN_OFF");
      fixture.bring("https://burp.test/api/private?token=NEVER_RETAIN_IMPORT");
      var store=(DiskTrafficStore)get(ext,"trafficStore");
      check(store.size()==0&&fixture.responseReads.get()==0,"OFF accounting never serializes or analyzes excluded response bytes");
      check(metric(ext,"received")==1&&metric(ext,"offExcluded")==1&&metric(ext,"importOffExcluded")==1,
          "OFF live callbacks and manual imports have separate exclusion counts");
      edt(()->fixture.mode().doClick(0));
      fixture.send("https://outside.test/private?token=NEVER_RETAIN_SCOPE");
      fixture.bring("https://outside.test/private");
      check(store.size()==0&&metric(ext,"scopeExcluded")==1&&metric(ext,"importScopeExcluded")==1,
          "Scope exclusions do not become stored rows or storage failures");
      fixture.send("https://burp.test/api/observed");
      fixture.bring("https://burp.test/api/imported");
      check(store.size()==2&&metric(ext,"storedResponses")==1&&metric(ext,"importStored")==1,
          "successful automatic and manual storage are attributed independently");
      check(metric(ext,"received")==3&&metric(ext,"importAttempts")==3,
          "manual imports never inflate live response counts");
      check(metric(ext,"responseStoreFailures")==0&&((java.util.concurrent.atomic.AtomicInteger)get(ext,"dropped")).get()==0,
          "intentional OFF and Scope exclusions do not count as loss");
      String summary=(String)invoke(ext,"captureDiagnostics");
      check(summary.contains("자동 응답 수신 3")&&summary.contains("수동 가져오기 시도 3")&&summary.contains("현재 보관 2"),
          "diagnostics identify the populations and current retained count");
      check(!summary.contains("NEVER_RETAIN")&&!summary.contains("https://"),"capture accounting retains no excluded URL or token metadata");
      edt(()->{try{invoke(ext,"clearStoredTraffic");invoke(ext,"refreshTrafficInfo");}catch(Exception error){throw new RuntimeException(error);}});
      check(((DiskTrafficStore)get(ext,"trafficStore")).size()==0&&metric(ext,"storedResponses")==1&&metric(ext,"importStored")==1,
          "clearing current rows preserves explicitly cumulative success counters");
      check(fixture.mode().getToolTipText().contains("OFF 제외 1")&&fixture.mode().getToolTipText().contains("SCOPE 제외 1"),
          "existing capture control exposes policy exclusions without adding UI panels");
      ((CaptureObservations)get(ext,"captureObservations")).close();
      ((DiskTrafficStore)get(ext,"trafficStore")).close();
      fixture.send("https://burp.test/api/storage-failure");
      check(metric(ext,"responseStoreFailures")==1&&metric(ext,"storedResponses")==1,
          "failed disk append is a storage failure, not a stored response");
      HttpResponseReceived bad=proxy(HttpResponseReceived.class,(p,m,a)->{
        if(m.getName().equals("initiatingRequest"))throw new IllegalStateException("NEVER_LOG_PACKET");
        return def(m);
      });
      ext.handleHttpResponseReceived(bad);
      check(metric(ext,"captureDecisionErrors")==1&&metric(ext,"responseStoreFailures")==1,
          "request metadata failure is separate from disk storage failure");
      long accounted=metric(ext,"storedResponses")+metric(ext,"offExcluded")+metric(ext,"scopeExcluded")
          +metric(ext,"responseStoreFailures")+metric(ext,"captureDecisionErrors")+metric(ext,"shutdownExcluded");
      check(metric(ext,"received")==5&&accounted==5,"each completed live callback has one capture outcome");
    }
    System.out.println("CaptureProvenanceTest: "+checks+" checks passed");
  }
}
