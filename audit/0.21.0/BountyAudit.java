package kr.workbench;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.zip.*;import kr.payloadrunner.*;
public class BountyAudit extends CommunityExtensionAudit {
 static List<Map<String,Object>> observations=new ArrayList<>(); static List<BountyEvidence.Item> evidence=new ArrayList<>();
 static void obs(String id,Object value){observations.add(Map.of("id",id,"value",value));System.out.println("OBS "+id+" "+value);}
 static AuthJudgement.Rules rules(AuthPolicy.Access p,String owner){return new AuthJudgement.Rules(Set.of(),Map.of(),owner,p);}
 static void access(String id,String path,Account owner,Account visitor,AuthPolicy.Access policy,String ownerField,int status,String marker)throws Exception{
  Reply original=get(path,owner),target=get(path,visitor),recheck=get(path,owner);
  var result=AuthJudgement.compare(original.status(),original.body(),target.status(),target.body(),rules(policy,ownerField),visitor.owner(),original.status()==200&&recheck.status()==200&&original.body().equals(recheck.body()));
  verify(id+" target status",target.status()==status);if(status==200&&!marker.isEmpty())verify(id+" impact marker",target.body().contains(marker));
  authRows.add(Map.of("id",id,"path",path,"baselineAccount",owner.name(),"account",visitor.name(),"baselineStatus",original.status(),"status",target.status(),"state",result.state(),"reason",result.reason()));
  evidence.add(new BountyEvidence.Item(id+" owner baseline","GET",base+path,template("GET",path,owner,"",""),new String(original.raw(),StandardCharsets.UTF_8),"Owner baseline, rechecked with identical body: "+original.body().equals(recheck.body())));
  evidence.add(new BountyEvidence.Item(id,"GET",base+path,template("GET",path,visitor,"",""),new String(target.raw(),StandardCharsets.UTF_8),result.note()));
 }
 static String form(String s){return java.net.URLEncoder.encode(s,StandardCharsets.UTF_8);}
 static void panelAudit(Account alice,Account bob)throws Exception{
  burp.api.montoya.internal.ObjectFactoryLocator.FACTORY=proxy(burp.api.montoya.internal.MontoyaObjectFactory.class,(p,m,a)->m.getName().equals("requestOptions")?proxy(burp.api.montoya.http.RequestOptions.class,(pp,mm,aa)->pp):def(m));
  var http=proxy(burp.api.montoya.http.Http.class,(p,m,a)->{if(!m.getName().equals("sendRequest"))return def(m);var request=(burp.api.montoya.http.message.requests.HttpRequest)a[0];Reply rr=reply(send(request.toByteArray().getBytes()));return AuthRecordTest.rr(request,responseAdapter(rr));});
  var api=proxy(burp.api.montoya.MontoyaApi.class,(p,m,a)->switch(m.getName()){case "http"->http;case "scope"->proxy(burp.api.montoya.scope.Scope.class,(pp,mm,aa)->mm.getName().equals("isInScope")?aa[0].toString().startsWith(base+"/"):def(mm));default->def(m);});
  String path="/api/community/fixed/posts/102";Reply baseline=get(path,bob);var original=q(path,"GET",bob,"","");
  for(boolean matrix:List.of(false,true)){
   var ref=new java.util.concurrent.atomic.AtomicReference<AuthComparePanel>();edt(()->ref.set(new AuthComparePanel(api,()->List.of(AuthRecordTest.rr(original,responseAdapter(baseline))))));var panel=ref.get();int previous=sends;
   try{edt(()->{panel.observe(q("/api/auth/me","GET",alice,"",""),response(200,"{}"));panel.assign(0,panel.credentials.values().iterator().next());panel.ownerPath.setText("$.OWNER_ID");panel.profileOwners[0].setText("1");panel.policyRules.setText("GET "+path+" OWNER_ONLY");if(matrix)panel.matrixRules.setText("GET "+path+" DENY ALLOW DENY");panel.compareSelection();});AuthRecordTest.idle(panel);var packet=panel.runs.get(0).packets[0];verify("actual auth panel receives fixed 404 matrix="+matrix,packet.response!=null&&packet.response.statusCode()==404);obs("auth-panel-hidden-denial-matrix-"+matrix,Map.of("note",packet.note,"httpRequests",sends-previous));}finally{edt(panel::close);}
  }
 }
 public static void main(String[] args)throws Exception{int code=0;port=Integer.parseInt(args[0]);base="http://127.0.0.1:"+port;Path out=Path.of(args[1]);Files.createDirectories(out);
  try{
   Account alice=login("alice","1"),bob=login("bob","2"),admin=login("admin","3"),moder=login("moderator","4");
   for(String mode:List.of("vuln","fixed")){boolean vulnerable=mode.equals("vuln");String p="/api/community/"+mode;
    access(mode+"-private-post",p+"/posts/102",bob,alice,AuthPolicy.Access.OWNER_ONLY,"$.OWNER_ID",vulnerable?200:404,"BOB_PRIVATE_NOTE");
    access(mode+"-private-message",p+"/messages/202",bob,alice,AuthPolicy.Access.OWNER_ONLY,"$.SENDER_ID",vulnerable?200:404,"BOB_TO_ADMIN_PRIVATE_MESSAGE");
    access(mode+"-private-file",p+"/files/bob-private-file",bob,alice,AuthPolicy.Access.OWNER_ONLY,"",vulnerable?200:404,"BOB_PRIVATE_ATTACHMENT");
    access(mode+"-anonymous-file",p+"/files/bob-private-file",bob,guest,AuthPolicy.Access.OWNER_ONLY,"",vulnerable?200:404,"BOB_PRIVATE_ATTACHMENT");
    access(mode+"-admin-spoof",p+"/admin/users?role=ADMIN",admin,alice,AuthPolicy.Access.UNSPECIFIED,"",vulnerable?200:403,"alice@lab.invalid");
    access(mode+"-public-policy",p+"/posts/101",alice,guest,AuthPolicy.Access.PUBLIC,"$.OWNER_ID",200,"커뮤니티");
    Reply pub=get(p+"/posts/101",bob);verify(mode+" PUBLIC avoids owner false positive",AuthJudgement.compare(200,pub.body(),200,pub.body(),rules(AuthPolicy.Access.PUBLIC,"$.OWNER_ID"),"2").state().equals("공개/공유 허용"));
    Reply prv=get(p+"/posts/102",admin);var matrix=BountyMatrix.parse("GET "+p+"/posts/102 DENY ALLOW DENY");obs(mode+"-admin-matrix",matrix.assess(matrix.expected("GET",base+p+"/posts/102",1),prv.status(),prv.body(),rules(AuthPolicy.Access.OWNER_ONLY,"$.OWNER_ID")));
    for(boolean adaptive:List.of(false,true)){
     var sql=scan(mode+"-sqli-error-"+adaptive,Engine.Profile.SQLI,template("GET",p+"/search?q=§PAYLOAD§",alice,"",""),"Burp",List.of("'"),"",adaptive);verify(mode+" sql error "+adaptive,candidate(sql,"SQL 오류 후보")==vulnerable);
     var bool=scan(mode+"-sqli-boolean-"+adaptive,Engine.Profile.SQLI,template("GET",p+"/search?q=§PAYLOAD§",alice,"",""),"Burp",List.of("{{BASE}}%' AND 1=1 -- ","{{BASE}}%' AND 1=2 -- "),"",adaptive);obs(mode+"-boolean-detected-"+adaptive,candidate(bool,"SQL 조건 차이"));
     verify(mode+" boolean returns responses "+adaptive,bool.stream().allMatch(r->r.response()!=null));
     verify(mode+" boolean candidate matches control "+adaptive,candidate(bool,"SQL 조건 차이")==vulnerable);
     var xss=scan(mode+"-reflected-xss-"+adaptive,Engine.Profile.XSS,template("GET","/"+mode+"/preview?text=§PAYLOAD§",alice,"",""),"hello",List.of("<img src=x onerror=console.log('{{MARKER}}')>"),"",adaptive);verify(mode+" reflected xss "+adaptive,candidate(xss,"XSS 반사 후보")==vulnerable);
    }
    // Search without an injection: check whether authorization is already missing.
    Reply search=get(p+"/search?q=",guest);verify(mode+" search private body disclosure",search.body().contains("BOB_PRIVATE_NOTE")==vulnerable);obs(mode+"-guest-search",Map.of("status",search.status(),"privateBodyLeaked",search.body().contains("BOB_PRIVATE_NOTE")));
    evidence.add(new BountyEvidence.Item(mode+" anonymous search","GET",base+p+"/search?q=",template("GET",p+"/search?q=",guest,"",""),new String(search.raw(),StandardCharsets.UTF_8),"No injection; private body present="+search.body().contains("BOB_PRIVATE_NOTE")));
    String createBody="title=AuditTemporary&body=ORIGINAL&visibility=PRIVATE&category=general";
    Reply created=post("/api/community/fixed/posts",bob,createBody,"application/x-www-form-urlencoded");verify(mode+" create disposable post",created.status()==200);String id=mapper.readValue(created.body(),Map.class).get("id").toString();
    Reply edit=post(p+"/posts/"+id+"/edit",alice,createBody.replace("ORIGINAL","CHANGED_BY_ALICE"),"application/x-www-form-urlencoded");Reply readback=get("/api/community/fixed/posts/"+id,bob);verify(mode+" cross-account edit status",edit.status()==(vulnerable?200:403));verify(mode+" edit actual state",readback.body().contains("CHANGED_BY_ALICE")==vulnerable);
    evidence.add(new BountyEvidence.Item(mode+" unauthorized write readback","GET",base+"/api/community/fixed/posts/"+id,template("GET","/api/community/fixed/posts/"+id,bob,"",""),new String(readback.raw(),StandardCharsets.UTF_8),"After Alice write: CHANGED_BY_ALICE present="+readback.body().contains("CHANGED_BY_ALICE")));
    Account noCsrf=new Account(bob.name(),bob.cookie(),"",bob.owner());Reply noToken=post(p+"/posts/"+id+"/edit",noCsrf,createBody,"application/x-www-form-urlencoded");verify(mode+" missing csrf enforcement",noToken.status()==(vulnerable?200:403));
    Reply upload=post(p+"/posts/"+id+"/files?name=audit.html",bob,"<html><body>UPLOAD_AUDIT</body></html>","text/html");verify(mode+" active content upload",upload.status()==(vulnerable?200:415));if(vulnerable){String url=mapper.readValue(upload.body(),Map.class).get("url").toString();Reply uploaded=get(url,guest);verify("uploaded HTML served anonymously inline",uploaded.status()==200&&uploaded.headers().contains("text/html")&&uploaded.headers().contains("inline")&&uploaded.body().contains("UPLOAD_AUDIT"));}
    Reply remove=post(p+"/posts/"+id+"/delete",alice,"","");verify(mode+" cross-account delete status",remove.status()==(vulnerable?200:403));verify(mode+" deletion confirmed by owner",get("/api/community/fixed/posts/"+id,bob).status()==(vulnerable?404:200));
    if(!vulnerable)post("/api/community/fixed/posts/"+id+"/delete",bob,"","");
   }
   var stored=scan("stored-xss-json",Engine.Profile.XSS,template("POST","/api/community/vuln/posts/101/comments",alice,"body=§PAYLOAD§","application/x-www-form-urlencoded"),"baseline",List.of("<img src=x onerror=console.log('{{MARKER}}')>"),"",false);
   verify("stored marker persists in separate read",get("/api/community/vuln/posts/101",alice).body().contains("onerror=console.log"));obs("stored-xss-single-request-detected",candidate(stored,"XSS 반사 후보"));
   panelAudit(alice,bob);
   // Production tracking/evidence model: observed limitations, not desired new requirements.
   ActivityTracker activity=new ActivityTracker();AssessmentTracker t=activity.assessments;
   String a=t.register(new AssessmentTracker.AccountInput("Alice",base,"USER","A",false)),b=t.register(new AssessmentTracker.AccountInput("Bob",base,"USER","B",false));
   t.bind(List.of(new AssessmentTracker.Binding(a,base,Map.of("cookie",alice.cookie()),Set.of("cookie"),Set.of("JSESSIONID"))));
   String url=base+"/api/community/fixed/posts?lab_hidden=first";activity.observe(q("/api/community/fixed/posts?lab_hidden=first","GET",alice,"",""),"AUDIT");
   String job=t.createTask("Account evidence gate audit","local fixture",Set.of(a,b),true);t.addTarget(job,"GET",url);t.complete(a,"GET",url,activity.parameterNames("GET",url),true);t.complete(b,"GET",url,activity.parameterNames("GET",url),true);
   obs("task-before-evidence",t.tasks());
   Reply r=get("/api/community/fixed/posts?lab_hidden=first",alice);var item=new BountyEvidence.Item("Alice only","GET",url,template("GET","/api/community/fixed/posts?lab_hidden=first",alice,"",""),new String(r.raw(),StandardCharsets.UTF_8),"one of two required accounts",a);
   Path zip=out.resolve("single-account-evidence.zip");BountyEvidence.save(zip,"Evidence gate audit","fixture","Alice evidence only",List.of(item),Set.of(),List.of(alice.cookie(),alice.csrf()),true,activity.save(),null);
   t.evidenceSaved(Core.hash(Files.readAllBytes(zip)),zip.getFileName().toString(),List.of(item));t.completeTask(job,true);obs("task-with-one-of-two-evidence",t.tasks());
   t.editAccount(a,"Alice","ADMIN","OTHER_ORG");obs("task-after-role-tenant-change",t.tasks());
   activity.observe(q("/api/community/fixed/posts?lab_hidden=second","GET",alice,"",""),"AUDIT");obs("account-after-value-change",t.view(a,"GET",url,activity.parameterNames("GET",url)));obs("task-after-value-change",t.tasks());
   String saved=activity.save();ActivityTracker restored=new ActivityTracker();restored.load(saved);verify("credentials not serialized",!saved.contains(alice.cookie())&&!saved.contains(alice.csrf()));verify("binding not restored",restored.assessments.match(q("/api/community/fixed/posts","GET",alice,"","")).isEmpty());
   Path temporary=out.resolve("temporary-evidence.zip");Files.copy(zip,temporary,StandardCopyOption.REPLACE_EXISTING);Files.delete(temporary);obs("evidence-reference-model","Model stores artifact id/name; no filesystem existence revalidation. Actual final evidence is retained.");
   var groups=UrlGroups.groups(List.of(base+"/api/community/fixed/posts/101",base+"/api/community/fixed/posts/102"),Set.of());obs("public-private-grouping",groups);verify("public private grouped together",groups.size()==1);
   BountyEvidence.save(out.resolve("http-evidence.zip"),"Community access control comparison","Private post/message/file exposure and fixed controls","Owner baseline; alternate account; owner recheck. See results.json.",evidence,Set.of(),List.of(alice.cookie(),bob.cookie(),admin.cookie(),moder.cookie(),alice.csrf(),bob.csrf()),true,"{}",null);
   try(ZipFile z=new ZipFile(out.resolve("http-evidence.zip").toFile())){StringBuilder content=new StringBuilder();for(var e:Collections.list(z.entries()))content.append(new String(z.getInputStream(e).readAllBytes(),StandardCharsets.UTF_8));verify("saved evidence excludes live cookies",!content.toString().contains(alice.cookie())&&!content.toString().contains(bob.cookie()));}
  }catch(Throwable ex){ex.printStackTrace();obs("fatal",ex.toString());code=1;}finally{Map<String,Object> result=new LinkedHashMap<>();result.put("extensionVersion","0.21.0");result.put("labVersion","3.4.0");result.put("mode","production release JAR + actual loopback Spring/Tomcat/H2 HTTP + API adapters; no native Burp or browser");result.put("httpRequests",sends);result.put("checks",assertions);result.put("passed",assertions.stream().filter(x->Boolean.TRUE.equals(x.get("passed"))).count());result.put("failed",assertions.stream().filter(x->Boolean.FALSE.equals(x.get("passed"))).count());result.put("auth",authRows);result.put("payload",payloadRows);result.put("observations",observations);mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("results.json").toFile(),result);}
  System.exit(code);
 }
}
