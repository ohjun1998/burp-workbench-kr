package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.internal.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** The production comparator and cookie combination UI cannot send their first control before consent. */
public final class AuthActiveApprovalTest extends WorkbenchTest {
  public static void main(String[] args)throws Exception {
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->
        m.getName().equals("requestOptions")?proxy(RequestOptions.class,(pp,mm,aa)->pp):def(m));
    AtomicInteger sent=new AtomicInteger(),prompts=new AtomicInteger();
    AtomicReference<ActiveRequestApproval.Plan> shown=new AtomicReference<>();
    var baseline=response(200,"{\"owner\":\"alice\"}");
    var http=proxy(Http.class,(p,m,a)->{
      if(!m.getName().equals("sendRequest"))return def(m);
      sent.incrementAndGet();return AuthRecordTest.rr((HttpRequest)a[0],baseline);
    });
    var api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http")?http:def(m));
    var original=AuthRecordTest.q("ALICE");
    AtomicReference<AuthComparePanel> box=new AtomicReference<>();
    var previous=ActiveRequestApproval.prompt;
    edt(()->{
      var panel=new AuthComparePanel(api,()->List.of(AuthRecordTest.rr(original,baseline)));box.set(panel);
      panel.observe(AuthRecordTest.q("BOB"),baseline);
      panel.assign(0,panel.credentials.values().iterator().next());panel.anonymous(3);
    });
    var panel=box.get();
    try {
      ActiveRequestApproval.prompt=(owner,plan)->{check(SwingUtilities.isEventDispatchThread(),"worker comparison confirmation runs on EDT");prompts.incrementAndGet();shown.set(plan);return false;};
      edt(panel::compareSelection);AuthRecordTest.idle(panel);
      check(sent.get()==0,"denial blocks fresh baseline, identity, comparison and effect requests");
      check(panel.runs.get(0).currentBaseline==null&&panel.runs.get(0).note.equals("중지"),"denied original remains a cancelled unexecuted comparison");
      check(panel.recording.get()==null,"denial stops automatic admission as well as the current original");
      check(shown.get().requestPlan().contains("500ms")&&shown.get().requestPlan().contains("상한"),"confirmation includes conservative volume and actual spacing");
      check(!shown.get().toString().contains("ALICE")&&!shown.get().toString().contains("BOB"),"confirmation never contains session values");
      ActiveRequestApproval.prompt=(owner,plan)->{prompts.incrementAndGet();shown.set(plan);return true;};
      edt(panel::compareSelection);AuthRecordTest.idle(panel);
      check(sent.get()>0,"explicit consent lets the existing bounded comparator perform its controls");
      int approvedSends=sent.get(),before=prompts.get();
      ActiveRequestApproval.prompt=(owner,plan)->{prompts.incrementAndGet();return false;};
      edt(panel::compareSelection);AuthRecordTest.idle(panel);
      check(prompts.get()==before+1&&sent.get()==approvedSends,"same original in a new run requires fresh consent and cancellation sends nothing");
      edt(()->{
        panel.assign(0,new AuthComparePanel.Candidate(99,"cookie-fixture",AuthComparePanel.origin(original),Map.of("cookie","SID=BOB"),1,""));
        panel.cookieSessions.url.setText("https://auth.test/me");
        panel.cookieSessions.identityPath.setText("$.owner");panel.cookieSessions.expected.setText("alice");
        panel.cookieSessions.start();
      });
      check(sent.get()==approvedSends&&!panel.cookieSessions.busy,"cookie combination denial precedes its worker and all128 possible requests");
      AtomicBoolean changedRejected=new AtomicBoolean();
      ActiveRequestApproval.prompt=(owner,plan)->{shown.set(plan);panel.cookieSessions.url.setText("https://auth.test/changed");return true;};
      edt(()->{try{panel.cookieSessions.start();}catch(IllegalArgumentException expected){changedRejected.set(true);}});
      check(changedRejected.get()&&sent.get()==approvedSends,"changing cookie target during confirmation invalidates approval before sending");
      check(shown.get().requestPlan().contains("128")&&shown.get().requestPlan().contains("120ms"),"cookie plan discloses request budget and minimum interval");
    }finally{ActiveRequestApproval.prompt=previous;edt(panel::close);WorkbenchScope.release(api);}
    System.out.println("AuthActiveApprovalTest: "+checks+" checks passed");
  }
}
