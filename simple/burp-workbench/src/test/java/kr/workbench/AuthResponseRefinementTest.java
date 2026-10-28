package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.util.*;
import java.util.concurrent.atomic.*;
import static kr.workbench.WorkbenchTest.*;

/** Response format changes and bounded write observations must retain uncertainty. */
public final class AuthResponseRefinementTest {
  static int checks;
  static void ok(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
  static void rejects(BountyFeaturesTest.Throwing action,String message){try{action.run();throw new AssertionError(message);}catch(AssertionError e){throw e;}catch(Exception expected){ok(true,message);}}
  public static void main(String[] args)throws Exception{
    BountyFeaturesTest.factory();
    var api=proxy(MontoyaApi.class,(p,m,a)->def(m));
    var request=BountyFeaturesTest.rq("http://refinement.test/export","GET","",List.of());
    AtomicReference<AuthComparePanel> box=new AtomicReference<>();
    edt(()->{var panel=new AuthComparePanel(api,List::of);box.set(panel);panel.observe(BountyFeaturesTest.rq("http://refinement.test/export","GET","",List.of(header("Cookie","SID=target"))),response(200,"FILE"));panel.assign(0,panel.credentials.values().iterator().next());panel.matrixRules.setText("GET /export DENY UNKNOWN UNKNOWN UNKNOWN");});
    var panel=box.get();
    try{
      for(String baseline:List.of("owner,record\n1,PRIVATE_FILE\n","PRIVATE_FILE\nBOB")){
        var run=new AuthComparePanel.Run(1,request,response(200,baseline),panel.context());
        for(String error:List.of("FORBIDDEN","UNAUTHORIZED")){
          var f=AuthComparison.assess(run,0,request,response(200,"{\"trainingOnly\":true,\"error\":\""+error+"\"}"),false);
          ok(f.kind()==AuthFinding.Kind.BLOCKED&&f.reason().contains("JSON"),"CSV/file baseline preserves explicit JSON denial "+error);
        }
        var leak=AuthComparison.assess(run,0,request,response(200,"{\"error\":\"FORBIDDEN\",\"data\":{\"secret\":\"PRIVATE_FILE\"}}"),false);
        ok(leak.kind()==AuthFinding.Kind.DENIAL_DATA&&leak.verdict()==AuthVerdict.REVIEW,"denial plus data stays review across formats");
      }
      var run=new AuthComparePanel.Run(2,request,response(200,"FILE"),panel.context());
      ok(AuthComparison.assess(run,0,request,response(200,"{\"data\":\"FORBIDDEN\"}"),false).verdict()==AuthVerdict.SUSPECT,"denial word in data is not an error rule");
      ok(AuthComparison.assess(run,0,request,response(200,"{\"error\":\"FORBIDDEN\""),false).verdict()!=AuthVerdict.BLOCKED,"malformed JSON is not a logical denial");
      ok(AuthComparison.assess(run,0,request,response(200,"{\"error\":\"UNKNOWN_ERROR\"}"),false).verdict()!=AuthVerdict.BLOCKED,"unconfigured error is not declared blocked");
      edt(()->panel.responseDenialRules.setText("BODY: FORBIDDEN"));
      run=new AuthComparePanel.Run(3,request,response(200,"FILE"),panel.context());
      ok(AuthComparison.assess(run,0,request,response(403,"{\"error\":\"FORBIDDEN\",\"content\":\"private\"}"),false).kind()==AuthFinding.Kind.DENIAL_DATA,"text fingerprint cannot hide denial and data conflict");
      var rules=AuthJudgement.rules("","error=FORBIDDEN","$.ownerId");
      ok(AuthJudgement.compare(200,"FILE",200,"{\"error\":\"FORBIDDEN\",\"ownerId\":2}",rules,"1").kind()==AuthFinding.Kind.DENIAL_DATA,"configured owner data conflicts with denial");
      var critical=new AuthJudgement.Rules(Set.of(),rules.denied(),"",AuthPolicy.Access.UNSPECIFIED,Set.of("$.secret"),false);
      ok(AuthJudgement.compare(200,"FILE",200,"{\"error\":\"FORBIDDEN\",\"secret\":\"x\"}",critical,"").kind()==AuthFinding.Kind.DENIAL_DATA,"configured critical field conflicts with denial");
      ok(AuthJudgement.compare(200,"FILE",200,"{\"error\":\"FORBIDDEN\",\"data\":[],\"result\":null}",rules,"").kind()==AuthFinding.Kind.BLOCKED,"empty payload has no observed data");
      ok(AuthJudgement.compare(401,"{}",200,"{\"error\":\"FORBIDDEN\"}",rules,"").kind()==AuthFinding.Kind.BASELINE,"invalid reference remains invalid");
      ok(AuthJudgement.compare(200,"{\"error\":\"FORBIDDEN\"}",403,"{}",rules,"").kind()==AuthFinding.Kind.BASELINE,"denied JSON reference cannot become green");

      HttpRequest write=BountyFeaturesTest.rq("http://refinement.test/write","POST","{}",List.of());
      var config=new AuthEffectCheck.Config(true,"POST","/write","http://refinement.test/state","$.state","1",AuthEffectCheck.Mode.EQUALS,2);
      var before=AuthEffectCheck.snapshot(config,request,response(200,"{\"state\":0}"));
      AtomicInteger reads=new AtomicInteger();
      var unchanged=AuthEffectCheck.after(config,before,q->{reads.incrementAndGet();return response(200,"{\"state\":0}");},()->false);
      ok(unchanged.outcome()==AuthEffectCheck.Outcome.UNCHANGED&&reads.get()==2,"unchanged state uses only bounded followup reads");
      var blocked=new AuthFinding(AuthFinding.Kind.BLOCKED,"HTTP 403","");
      var missing=new AuthFinding(AuthFinding.Kind.REVIEW,"HTTP 404","");
      var success=new AuthFinding(AuthFinding.Kind.WRITE_UNVERIFIED,"HTTP 200","");
      ok(AuthEffectCheck.finding(unchanged,BountyMatrix.Expected.DENY,blocked,response(403,"{}")).verdict()==AuthVerdict.BLOCKED,"explicit denial and unchanged monitored state retain denial evidence");
      ok(AuthEffectCheck.finding(unchanged,BountyMatrix.Expected.UNKNOWN,blocked,response(403,"{}")).kind()==AuthFinding.Kind.WRITE_UNCHANGED,"unknown policy and no change remain inconclusive");
      for(int status:List.of(200,404,410))ok(AuthEffectCheck.finding(unchanged,BountyMatrix.Expected.DENY,missing,response(status,"{}")).kind()==AuthFinding.Kind.WRITE_UNCHANGED,"no change alone cannot establish blocking HTTP "+status);
      ok(AuthEffectCheck.finding(unchanged,BountyMatrix.Expected.ALLOW,success,response(200,"{}")).kind()==AuthFinding.Kind.WRITE_UNCHANGED,"allowed request without observed result remains review");
      ok(AuthEffectCheck.finding(unchanged,BountyMatrix.Expected.DENY,blocked,response(202,"{}")).kind()==AuthFinding.Kind.WRITE_PENDING,"accepted async operation cannot become green from immediate no change");
      var conflict=new AuthFinding(AuthFinding.Kind.DENIAL_DATA,"denial plus data","");
      ok(AuthEffectCheck.finding(unchanged,BountyMatrix.Expected.DENY,conflict,response(200,"{}")).kind()==AuthFinding.Kind.DENIAL_DATA,"unchanged observer cannot clear response data conflict");
      var partial=AuthEffectCheck.after(config,before,q->response(200,"{\"state\":2}"),()->false);
      ok(partial.outcome()==AuthEffectCheck.Outcome.UNEXPECTED&&AuthEffectCheck.finding(partial,BountyMatrix.Expected.DENY,blocked,response(403,"{}")).kind()==AuthFinding.Kind.WRITE_PARTIAL,"changed value outside expected state is a distinct review reason");
      var changed=AuthEffectCheck.after(config,before,q->response(200,"{\"state\":1}"),()->false);
      ok(AuthEffectCheck.finding(changed,BountyMatrix.Expected.DENY,blocked,response(403,"{}")).verdict()==AuthVerdict.SUSPECT,"403 followed by actual expected mutation remains a finding");
      rejects(()->AuthEffectCheck.after(config,before,q->response(500,"{}"),()->false),"failed observer cannot yield unchanged evidence");
      var unknown=new AuthEffectCheck.Evidence(config,before,null,AuthEffectCheck.Outcome.UNKNOWN,"read failed");
      ok(AuthEffectCheck.finding(unknown,BountyMatrix.Expected.DENY,blocked,response(403,"{}")).kind()==AuthFinding.Kind.WRITE_READ_FAILED,"missing after snapshot has a distinct read failure reason");
      AtomicInteger cancelledReads=new AtomicInteger();
      rejects(()->AuthEffectCheck.after(config,before,q->{cancelledReads.incrementAndGet();return response(200,"{}");},()->true),"cancel before observer prevents read");
      ok(cancelledReads.get()==0,"cancelled observation issues no transport");
      rejects(()->AuthEffectCheck.before(config,request,q->response(200,"{\"state\":1}")),"already achieved state stops before write");
      for(var kind:List.of(AuthFinding.Kind.DENIAL_DATA,AuthFinding.Kind.WRITE_UNVERIFIED,AuthFinding.Kind.WRITE_UNCHANGED,AuthFinding.Kind.WRITE_PENDING,AuthFinding.Kind.WRITE_PARTIAL,AuthFinding.Kind.WRITE_READ_FAILED,AuthFinding.Kind.WRITE_SETUP_FAILED))ok(new AuthFinding(kind,"HTTP 200","").verdict()==AuthVerdict.REVIEW,"typed review reason has stable verdict "+kind);
      edt(()->{panel.responseDenialRules.setText("");panel.matrixRules.setText("POST /write DENY UNKNOWN UNKNOWN UNKNOWN");});
      var writeRun=new AuthComparePanel.Run(4,write,response(200,"{\"ok\":true}"),panel.context());
      ok(AuthComparison.assess(writeRun,0,write,response(200,"{\"ok\":true}"),false).kind()==AuthFinding.Kind.WRITE_UNVERIFIED,"successful write response requires effect evidence");
    }finally{edt(panel::close);}
    System.out.println("AuthResponseRefinementTest: "+checks+" checks passed");
  }
}
