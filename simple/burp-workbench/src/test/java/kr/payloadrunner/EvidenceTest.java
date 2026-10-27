package kr.payloadrunner;
import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.atomic.*;
import static kr.payloadrunner.Engine.*;
public final class EvidenceTest {
 static void ok(String n,boolean v){EngineTest.assertTest("evidence: "+n,v);}
 static List<Row> rows;static Adaptive.Result run(Config c,Transport tx,Adaptive.Options opts)throws Exception{rows=new ArrayList<>();return Adaptive.run(c,tx,new AtomicBoolean(),rows::add,opts);}
 static boolean has(String s){return rows.stream().anyMatch(r->r.verdict().contains(s));}
 static byte[] raw(int status,String body){return EngineTest.raw(status,"text/plain",body);}
 static Config c(Profile p,List<String> payloads){return AdaptiveTest.config(p,payloads,100);}
 public static void run()throws Exception{
  var options=Adaptive.Options.basic();List<String> sent=new ArrayList<>();
  var result=run(c(Profile.DOWNLOAD,List.of("../../etc/passwd","other")),r->{String q=AdaptiveTest.value(r);sent.add(q);return raw(200,q.contains("passwd")?"root:x:0:0:root:/root:/bin/bash\n":"missing");},options);
  ok("download includes missing sibling and retains two file observations",result.requests()==6&&has("파일 내용 후보 · 재현")&&sent.stream().anyMatch(s->s.startsWith("../../etc/PR_MISSING_")));
  result=run(c(Profile.TRAVERSAL,List.of("../../etc/passwd","other")),r->{String q=AdaptiveTest.value(r);return raw(200,q.contains("../")?"root:x:0:0:root:/root:/bin/bash\n":"baseline");},options);ok("same generic content for nonexistent traversal not corroborated",!has("파일 내용 후보 · 재현")&&has("파일 근거 판정 보류"));
  result=run(c(Profile.DOWNLOAD,List.of("../../etc/passwd")),r->raw(AdaptiveTest.value(r).contains("PR_MISSING_")?403:200,AdaptiveTest.value(r).contains("passwd")?"root:x:0:0:root:/root:/bin/bash\n":"normal"),options);ok("blocked missing-file control cannot corroborate",!has("파일 내용 후보 · 재현")&&result.reason().contains("403"));
  sent.clear();result=run(c(Profile.REDIRECT,List.of("https://example.invalid/probe/{{MARKER}}","//example.invalid/probe/{{MARKER}}")),r->{String q=AdaptiveTest.value(r);sent.add(q);String location=q.startsWith("https://")?q:"/home";return ("HTTP/1.1 302 Found\r\nLocation: "+location+"\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);},options);ok("redirect corroborates a distinct destination host",has("리다이렉트 목적지 제어 후보 · 재현")&&sent.stream().anyMatch(s->s.contains("pr-second.invalid")));
  result=run(c(Profile.REDIRECT,List.of("https://example.invalid/probe/{{MARKER}}")),r->{String q=AdaptiveTest.value(r);return ("HTTP/1.1 302 Found\r\nLocation: "+(q.startsWith("https://")?"https://example.invalid/fixed":"/home")+"\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);},options);ok("hardcoded destination fails second-host control",!has(" · 재현"));
  sent.clear();result=run(c(Profile.COMMAND,defaults(Profile.COMMAND)),r->{String q=AdaptiveTest.value(r);sent.add(q);var m=java.util.regex.Pattern.compile("printf '([^']+)%s' '(PR_[a-f0-9]{16})'").matcher(q);return raw(200,m.find()?m.group(1)+m.group(2):"normal");},options);ok("command requires different composed output control",result.requests()==6&&has("출력 대조 일치")&&has("명령 출력 후보 · 재현")&&sent.stream().anyMatch(s->s.contains("PR_CTRL_%s")));
  result=run(c(Profile.COMMAND,List.of(defaults(Profile.COMMAND).get(0))),r->{String q=AdaptiveTest.value(r);var m=java.util.regex.Pattern.compile("'(PR_[a-f0-9]{16})'").matcher(q);return raw(200,q.contains("printf")&&m.find()?"PR_CMD_"+m.group(1):"normal");},options);ok("hardcoded command output fails alternate control",!has("명령 출력 후보 · 재현"));
  for(Profile p:List.of(Profile.XPATH,Profile.LDAP,Profile.NOSQL)){
   result=run(c(p,defaults(p)),r->{String q=AdaptiveTest.value(r);return raw(200,q.contains("'1'='2")||q.contains("PR_LDAP_NONEXISTENT")||q.contains("\"$eq\"")?"zero rows":"normal rows");},options);ok(p+" semantic pair reverse-order corroborated",has("조건 차이 재현 후보"));
   result=run(c(p,defaults(p)),r->raw(200,AdaptiveTest.value(r)),options);ok(p+" raw reflection is not semantic injection",!has("조건 차이 재현 후보"));
  }
  Config custom=new Config(Profile.CUSTOM,Encoding.URL,c(Profile.CUSTOM,List.of("probe")).template(),"alpha",List.of("probe","other"),0,100,"CONTROLLED_PROOF");
  result=run(custom,r->raw(200,AdaptiveTest.value(r).equals("probe")?"CONTROLLED_PROOF":"normal"),options);ok("custom explicit proof repeats and control excludes normal response",result.requests()==5&&has("지정 응답 문자열 후보 · 재현"));
  result=run(custom,r->raw(200,AdaptiveTest.value(r)),options);ok("custom payload reflection still not evidence",!has(" · 재현"));
  String tmpl="POST /upload?name=probe.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: text/html\r\n\r\n§PAYLOAD§";
  Config upload=new Config(Profile.UPLOAD_BODY,Encoding.RAW,tmpl,"baseline",defaults(Profile.UPLOAD_BODY),0,100,"");
  Map<String,String> storage=new HashMap<>();AtomicInteger seq=new AtomicInteger();
  Transport store=r->{String req=new String(r,StandardCharsets.UTF_8);if(req.startsWith("POST ")){String id="f"+seq.incrementAndGet();storage.put(id,req.substring(req.indexOf("\r\n\r\n")+4));return EngineTest.raw(200,"application/json","{\"url\":\"/files/"+id+"\"}");}String path=req.split(" ")[1];return EngineTest.raw(200,"text/html",storage.getOrDefault(path.substring(path.lastIndexOf('/')+1),"missing"));};
  result=run(upload,store,new Adaptive.Options(true,null,0));ok("upload reads returned relative URL and verifies exact body",has("업로드 파일 재조회 확인")&&has("업로드 활성 콘텐츠 제공 후보")&&result.requests()==5&&seq.get()==3);
  result=run(upload,r->{String req=new String(r,StandardCharsets.UTF_8);return req.startsWith("POST ")?EngineTest.raw(200,"application/json","{\"url\":\"https://external.invalid/file\"}"):raw(200,"unexpected");},new Adaptive.Options(true,null,0));ok("upload never fetches external response URL",result.requests()==4&&has("재조회 생략"));
  result=run(upload,r->EngineTest.raw(200,"application/json","{\"url\":\"//external.invalid/file\"}"),new Adaptive.Options(true,null,0));ok("protocol-relative readback blocked",result.requests()==4);
  result=run(upload,r->{String req=new String(r,StandardCharsets.UTF_8);return req.startsWith("POST ")?EngineTest.raw(200,"application/json","{\"url\":\"/files/a\"}"):raw(200,"unrelated content");},new Adaptive.Options(true,null,0));ok("upload accepted but mismatched retrieval not confirmed",has("업로드 재조회 미확인")&&!has("업로드 활성 콘텐츠 제공 후보"));
  upload=new Config(Profile.UPLOAD_BODY,Encoding.RAW,tmpl,"baseline",List.of("probe"),0,2,"");result=run(upload,store,new Adaptive.Options(true,null,0));ok("readback counts against same total budget",result.requests()==2&&!has("업로드 파일 재조회 확인"));
  int reusedPort;try(CallbackProbe cb=new CallbackProbe(0)){String url=cb.url("PR_1234567890abcdef");reusedPort=new URL(url).getPort();try(var in=new URL(url).openStream()){in.readAllBytes();}boolean conflict=false;try(var other=new CallbackProbe(reusedPort)){}catch(java.net.BindException e){conflict=true;}ok("active callback listener port cannot be reused",conflict);}
  try(var reopened=new CallbackProbe(reusedPort)){ok("callback listener reopens after accepted connection and close",true);}
  try(CallbackProbe cb=new CallbackProbe(0)){
   AtomicInteger fetches=new AtomicInteger();result=run(c(Profile.SSRF,defaults(Profile.SSRF)),r->{String q=AdaptiveTest.value(r);if(q.contains("/pr/")){fetches.incrementAndGet();try(var in=new URL(q).openStream()){in.readAllBytes();}}return raw(200,"constant target response");},new Adaptive.Options(false,cb,200));ok("blind SSRF identical responses verified via two distinct callback URLs",result.requests()==4&&fetches.get()==2&&has("SSRF HTTP 콜백 재현 후보"));
  }
  try(CallbackProbe cb=new CallbackProbe(0)){result=run(c(Profile.SSRF,defaults(Profile.SSRF)),r->raw(200,AdaptiveTest.value(r)),new Adaptive.Options(false,cb,50));ok("echoed SSRF URL never sends own callback or confirms SSRF",result.requests()==3&&!has("HTTP 콜백 관찰")&&result.reason().contains("콜백 없음"));}
  try(CallbackProbe cb=new CallbackProbe(0)){Config limited=new Config(Profile.SSRF,Encoding.URL,c(Profile.SSRF,defaults(Profile.SSRF)).template(),"alpha",defaults(Profile.SSRF),0,2,"");result=run(limited,r->raw(200,"normal"),new Adaptive.Options(false,cb,50));ok("callback requests obey budget",result.requests()==2);}
 }
}
