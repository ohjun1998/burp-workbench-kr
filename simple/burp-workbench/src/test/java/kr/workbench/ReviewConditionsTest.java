package kr.workbench;

import static kr.workbench.BountyFeaturesTest.*;
import burp.api.montoya.*;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Regressions for the observed 0.21 audit failures, including negative re-query cases. */
public final class ReviewConditionsTest extends WorkbenchTest {
 public static void main(String[] args)throws Exception{
  factory();matrix();accounts();evidence();ui();
  System.out.println("ReviewConditionsTest: "+checks+" checks passed");
 }
 static void matrix()throws Exception{
  for(String mode:List.of("same","deleted","expired","changed","targetChanged","sessionChanged","allow")){
   AtomicInteger calls=new AtomicInteger();String url="https://auth.test/objects/102";
   HttpRequest original=rq(url,"GET","",List.of(header("Cookie","SID=BOB")));
   var base=rs(200,"{\"ownerId\":2}",List.of());
   Http http=proxy(Http.class,(p,m,a)->{
    if(!m.getName().equals("sendRequest"))return def(m);
    HttpRequest q=(HttpRequest)a[0];int n=calls.incrementAndGet();
    if(n==1)return AuthRecordTest.rr(q,base);
    if(n==2)return AuthRecordTest.rr(q,rs(404,"{}",List.of()));
    HttpRequest actual=mode.equals("targetChanged")?rq("https://auth.test/other","GET","",List.of(header("Cookie","SID=BOB"))):mode.equals("sessionChanged")?rq(url,"GET","",List.of(header("Cookie","SID=ADMIN"))):q;
    return AuthRecordTest.rr(actual,rs(mode.equals("deleted")?404:mode.equals("expired")?401:200,mode.equals("changed")?"{\"ownerId\":3}":"{\"ownerId\":2}",List.of()));
   });
   MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http")?http:def(m));WorkbenchScope scope=WorkbenchScope.install(api);scope.add("https://auth.test/",true);
   AtomicReference<AuthComparePanel> box=new AtomicReference<>();
   edt(()->{var panel=new AuthComparePanel(api,()->List.of(AuthRecordTest.rr(original,base)));box.set(panel);panel.observe(rq(url,"GET","",List.of(header("Cookie","SID=ALICE"))),base);panel.assign(0,panel.credentials.values().iterator().next());panel.matrixRules.setText("GET /objects/102 "+(mode.equals("allow")?"ALLOW":"DENY")+" UNKNOWN UNKNOWN");panel.compareSelection();});
   AuthRecordTest.idle(box.get());String note=box.get().runs.get(0).packets[0].note;
   check(calls.get()==3,mode+": fresh reference, target request and owner re-query executed");
   check(box.get().runs.get(0).packets[0].finding().verdict()==(mode.equals("same")?AuthVerdict.BLOCKED:AuthVerdict.REVIEW),mode+": matrix keeps only verified denial and preserves uncertainty");
   if(mode.equals("allow"))check(note.contains("소유자 재조회"),"ALLOW retains existence evidence while requesting policy review");
   edt(box.get()::close);WorkbenchScope.release(api);
  }
  BountyMatrix matrix=BountyMatrix.parse("");var rules=AuthJudgement.rules("","","");
  check(matrix.assess(BountyMatrix.Expected.DENY,410,"",rules,true).contains("차단 근거 일치"),"verified 410 keeps denial evidence");
  check(matrix.assess(BountyMatrix.Expected.DENY,410,"",rules,false).contains("재검토"),"unverified 410 remains uncertain");
 }
 static void accounts(){
  ActivityTracker a=new ActivityTracker();AssessmentTracker t=a.assessments;
  String id=t.register(AccountTasksTest.account("Alice")),bob=t.register(AccountTasksTest.account("Bob"));String url="https://auth.test/items";
  t.bind(List.of(AccountTasksTest.binding(id,"SID=PRIVATE")));
  String job=t.createTask("strict","",Set.of(id),false);t.addTarget(job,"GET",url);t.taskRequirements(job,true,false,Set.of("URL:id"));
  var q=rq(url+"?id=101&page=1","GET","",List.of(header("Cookie","SID=PRIVATE")));
  a.observe(q,"PROXY");t.complete(id,"GET",url,List.of("URL:id","URL:page"),true);
  fails(()->t.completeTask(job,true),"strict work rejects unfinished inputs even with URL complete");
  t.completeInput(id,"GET",url,"URL:id",true);t.completeInput(id,"GET",url,"URL:page",true);t.completeTask(job,true);
  a.observe(rq(url+"?id=101&page=2","GET","",List.of(header("Cookie","SID=PRIVATE"))),"PROXY");
  check(t.tasks().get(0).state().equals("업무 완료"),"noncritical pagination does not reopen completed work");
  a.observe(rq(url+"?id=102&page=2","GET","",List.of(header("Cookie","SID=PRIVATE"))),"PROXY");
  check(t.tasks().get(0).state().equals("재검토 필요"),"critical input value change reopens work");
  check(t.view(id,"GET",url,List.of()).parameters().stream().anyMatch(p->p.key().equals("URL:id")&&p.state().equals("미점검")),"changed critical input loses input completion");
  t.completeInput(id,"GET",url,"URL:id",true);t.complete(id,"GET",url,List.of(),true);t.completeTask(job,true);
  t.complete(bob,"GET",url,List.of(),true);String snapshot=t.view(id,"GET",url,List.of()).completionContext();
  t.editAccount(id,"Alice renamed","USER","A");check(t.tasks().get(0).state().equals("업무 완료"),"name-only change keeps completion");
  t.editAccount(id,"Alice renamed","ADMIN","OTHER_ORG");
  check(t.tasks().get(0).state().equals("재검토 필요")&&t.view(id,"GET",url,List.of()).state().equals("재검토 필요"),"role/tenant change reopens account and task");
  check(t.view(id,"GET",url,List.of()).completionContext().equals(snapshot)&&snapshot.contains("USER")&&snapshot.contains("인증 연결=있음"),"completion retains old role/tenant and binding presence without credential");
  check(t.view(bob,"GET",url,List.of()).state().equals("점검 완료"),"unrelated account completion unaffected");
  String json=a.save();check(!json.contains("PRIVATE")&&!json.contains("inputHashes")&&!json.contains("fingerprint"),"serialized metadata excludes raw credentials and value fingerprints");
  ActivityTracker restored=new ActivityTracker();restored.load(json);check(restored.assessments.tasks().get(0).strictInputs()&&restored.assessments.tasks().get(0).criticalInputs().equals(Set.of("URL:id")),"strict options roundtrip");
  check(restored.assessments.view(id,"GET",url,List.of()).completionContext().equals(snapshot),"historical completion conditions roundtrip");
  t.complete(id,"GET",url,List.of(),true);t.completeTask(job,true);t.completeInput(id,"GET",url,"URL:page",false);check(t.tasks().get(0).state().equals("재검토 필요"),"undoing input completion reopens strict completed task");
  fails(()->t.taskRequirements(job,true,false,Set.of("id")),"important inputs require explicit location");
 }
 static void evidence()throws Exception{
  AssessmentTracker t=new AssessmentTracker();String a=t.register(AccountTasksTest.account("Alice")),b=t.register(AccountTasksTest.account("Bob"));String url="https://auth.test/items";
  String task=t.createTask("per pair","",Set.of(a,b),true);t.addTarget(task,"GET",url);t.taskRequirements(task,false,true,Set.of());t.complete(a,"GET",url,List.of(),true);t.complete(b,"GET",url,List.of(),true);
  Path zip=Files.createTempFile(Path.of("build"),"conditions-",".zip"),moved=zip.resolveSibling(zip.getFileName()+".moved");
  try{
   var ia=new BountyEvidence.Item("Alice","GET",url,"GET /items HTTP/1.1\r\n\r\n","HTTP/1.1 403\r\n\r\n","manual",a);var ib=new BountyEvidence.Item("Bob","GET",url,ia.request(),ia.response(),"manual",b);
   BountyEvidence.save(zip,"test","impact","steps",List.of(ia),Set.of(),Set.of(),false,"{}",null,List.of());String hash=AssessmentTracker.fileHash(zip);t.evidenceSaved(hash,zip.getFileName().toString(),List.of(ia));t.attachEvidenceFile(zip);
   check(t.tasks().get(0).evidenced()==1,"Alice-only ZIP covers one of two required pairs");fails(()->t.completeTask(task,true),"one account ZIP cannot satisfy all-account evidence gate");
   BountyEvidence.save(zip,"both","impact","steps",List.of(ia,ib),Set.of(),Set.of(),false,"{}",null,List.of());hash=AssessmentTracker.fileHash(zip);t.evidenceSaved(hash,zip.getFileName().toString(),List.of(ia,ib));t.attachEvidenceFile(zip);t.verifyEvidenceFiles();
   check(t.tasks().get(0).evidenced()==2,"one valid ZIP can cover both pairs");t.completeTask(task,true);
   AssessmentTracker restored=new AssessmentTracker();restored.load(MinerJson.parse(MetadataExport.json(t.save()),16*1024*1024,200000));check(restored.tasks().get(0).state().equals("재검토 필요")&&restored.tasks().get(0).evidenced()==0,"imported file metadata is not trusted as current disk verification");restored.attachEvidenceFile(zip);restored.completeTask(task,true);check(restored.tasks().get(0).state().equals("업무 완료"),"explicit SHA-matching reconnect restores file gate");
   Files.move(zip,moved);t.verifyEvidenceFiles();check(t.tasks().get(0).state().equals("재검토 필요"),"missing ZIP reopens completed task after verification");fails(()->t.completeTask(task,true),"missing ZIP blocks strict completion");
   t.attachEvidenceFile(moved);t.completeTask(task,true);check(t.tasks().get(0).state().equals("업무 완료"),"moved ZIP reconnects by SHA");
   Files.writeString(moved,"changed");t.verifyEvidenceFiles();check(t.tasks().get(0).evidenced()==0,"tampered file loses coverage");fails(()->t.attachEvidenceFile(moved),"unknown digest cannot reconnect to old evidence");
  }finally{Files.deleteIfExists(zip);Files.deleteIfExists(moved);}
 }
 static void ui()throws Exception{
  MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->def(m));edt(()->{ActivityPanel panel=new ActivityPanel(api);try{var ui=panel.assessmentPanel;String id=panel.tracker.assessments.register(AccountTasksTest.account("UI"));ui.refresh();ui.selectAccount(id);ui.taskTitle.setText("UI options");ui.requiredModel.addElement(panel.tracker.assessments.account(id));ui.requiredAccounts.setSelectedIndex(0);ui.strictInputs.setSelected(true);ui.allEvidence.setSelected(true);ui.criticalInputs.setText("id");fails(ui::saveTask,"invalid UI condition rejected before creation");check(panel.tracker.assessments.tasks().isEmpty(),"invalid settings do not leave partially created task");ui.criticalInputs.setText("URL:id");ui.saveTask();var task=panel.tracker.assessments.tasks().get(0);check(task.strictInputs()&&task.allEvidence()&&task.criticalInputs().contains("URL:id"),"UI saves strict task options");}finally{panel.close();}});
 }
}
