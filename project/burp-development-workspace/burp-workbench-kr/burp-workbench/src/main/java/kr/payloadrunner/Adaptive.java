package kr.payloadrunner;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static kr.payloadrunner.Engine.*;

/** Bounded, evidence-driven scheduling of one explicitly selected insertion point/profile.
 * No arbitrary volatile-field masking: unstable responses disable boolean comparisons.
 * A repeated observation is corroboration, never proof of exploitability.
 */
public final class Adaptive {
 public record Options(boolean verifyUpload,CallbackProbe callback,int callbackWaitMs){public Options{if(callbackWaitMs<0||callbackWaitMs>5000)throw new IllegalArgumentException("callback wait");}public static Options basic(){return new Options(false,null,0);}}
 public record Result(int requests,int uniquePayloads,int skipped,boolean stopped,boolean budgetReached,String reason){}
 final Options options;final Config c;final Transport tx;final AtomicBoolean stop;final Consumer<Row> sink;
 boolean htmlTextContext;
 int sent,index,failures;boolean halted,limited,stable;String reason="검사 목록 완료 · 미탐 가능";Response baseline;
 Adaptive(Config c,Transport tx,AtomicBoolean stop,Consumer<Row> sink,Options options){this.c=c;this.tx=tx;this.stop=stop;this.sink=sink;this.options=options;}
 public static Result run(Config c,Transport tx,AtomicBoolean stop,Consumer<Row> sink)throws Exception{return run(c,tx,stop,sink,Options.basic());}
 public static Result run(Config c,Transport tx,AtomicBoolean stop,Consumer<Row> sink,Options options)throws Exception{return new Adaptive(c,tx,stop,sink,options).execute();}
 boolean available(){if(sent>=c.budget()){limited=true;reason="총 요청 한도 · 재검증 미완료 가능";return false;}return !stop.get()&&!halted;}
 Row send(String pattern,String stage){
  if(!available())return null;
  String marker="PR_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
  if(pattern.contains("{{CALLBACK}}")){if(options.callback()==null)throw new IllegalArgumentException("로컬 콜백 수신기를 활성화하세요");pattern=pattern.replace("{{CALLBACK}}",options.callback().url(marker));}
  String payload=expand(pattern,c.baseline(),marker);if(c.profile()==Profile.XXE&&c.encoding()==Encoding.RAW)payload=XmlProbe.adapt(payload,c.baseline());byte[] req=render(c.template(),payload,c.encoding());
  return exchange(req,payload,marker,stage,true);
 }
 Row exchange(byte[] req,String payload,String marker,String stage,boolean judge){
  if(!available())return null;
  try{if(sent>0)delay(c.intervalMs(),stop);}catch(InterruptedException e){Thread.currentThread().interrupt();stop.set(true);return null;}
  if(!available())return null;
  long t=System.nanoTime();sent++;
  try{
   Response r=parse(tx.send(req));failures=0;
   String[] judgement=!judge?new String[]{"후속 조회 응답",stage}:baseline==null?new String[]{"기준 응답","원래 값"}:classify(c,baseline,r,payload,marker);
   Row row=new Row(++index,payload,(System.nanoTime()-t)/1_000_000,r,judgement[0],stage+" · "+judgement[1],req);
   emit(row);
   if(stopStatus(r.status())){halted=true;reason=stopReason(r.status());}
   return row;
  }catch(Exception e){if(e instanceof InterruptedException){Thread.currentThread().interrupt();stop.set(true);}if(isTimeout(e)){halted=true;reason="응답 대기시간 초과/응답 없음";}failures++;Row row=new Row(++index,payload,(System.nanoTime()-t)/1_000_000,null,"전송/분석 오류",stage+" · "+e.getClass().getSimpleName()+": "+Objects.toString(e.getMessage(),""),req);emit(row);if(failures>=3){halted=true;reason="연속 전송/분석 오류 3회";}return row;}
 }
 void emit(Row r){sink.accept(new Row(r.index(),r.payload(),r.millis(),compact(r.response()),r.verdict(),r.evidence(),r.request()));}
 void note(Row r,String verdict,String evidence){emit(new Row(r.index(),r.payload(),r.millis(),r.response(),verdict,evidence,r.request()));}
 boolean evidence(Row r){if(r==null||r.response()==null)return false;return switch(r.verdict()){
  case "XSS 반사 후보","SQL 오류 후보","표현식 평가 후보","명령 출력 후보","리다이렉트 목적지 제어 후보","응답 헤더 삽입 후보","파일 내용 후보","지정 파일 내용 발견"->true;
  default->r.verdict().endsWith(" 오류 후보");
 };}
 boolean comparable(Row r){return r!=null&&r.response()!=null&&r.response().decoded()&&!r.response().truncated()&&r.response().status()>=200&&r.response().status()<300;}
 String norm(Row r){return normalized(r.response().text(),r.payload());}
 boolean pair(Row truth,Row falsehood){return stable&&comparable(truth)&&comparable(falsehood)&&truth.response().status()==baseline.status()&&falsehood.response().status()==baseline.status()&&sameBody(truth.response().text(),truth.payload(),baseline.text(),c.baseline())&&!sameBody(truth.response().text(),truth.payload(),falsehood.response().text(),falsehood.payload());}
 boolean sqlPair(Row truth,Row falsehood){return stable&&comparable(truth)&&falsehood!=null&&falsehood.response()!=null&&usable(falsehood.response())&&truth.response().status()==baseline.status()&&(falsehood.response().status()==baseline.status()||falsehood.response().status()==404||falsehood.response().status()==410)&&sameBody(truth.response().text(),truth.payload(),baseline.text(),c.baseline())&&(truth.response().status()!=falsehood.response().status()||!sameBody(truth.response().text(),truth.payload(),falsehood.response().text(),falsehood.payload()));}
 Result execute(){
  LinkedHashSet<String> unique=new LinkedHashSet<>();for(String p:c.payloads())if(!p.isBlank())unique.add(p);
  // Exact concrete duplicates, including a repeated baseline, need no new request.
  Set<String> used=new HashSet<>();List<String> plan=new ArrayList<>(unique);
  Row first=send(c.baseline(),"기준 수집");if(first==null||first.response()==null)return result(unique.size(),0);
  baseline=first.response();
  boolean mutation=c.profile()==Profile.UPLOAD_NAME||c.profile()==Profile.UPLOAD_BODY;
  if(!mutation&&available()){
   Row again=send(c.baseline(),"기준 안정성 검사");stable=comparable(first)&&comparable(again)&&baseline.status()==again.response().status()&&baseline.text().equals(again.response().text());
   if(again!=null)note(again,"기준 재검증",stable?"기준 2회 동일 · 조건 차이 비교 가능":"동적/잘림/오류 응답 · 조건 차이 판정 비활성화; 출력·헤더 증거 검사는 계속");
  }
  if(c.profile()==Profile.SSRF&&options.callback()!=null&&available()){
   Row probe=send("{{CALLBACK}}","전용 HTTP 콜백 탐색");if(probe==null||halted)return result(unique.size(),0);CallbackProbe.Hit hit=callbackHit(probe);
   if(hit!=null){note(probe,"HTTP 콜백 관찰 후보","고유 URL로 GET 수신 · 출처 "+hit.source()+" · 확장 자체는 콜백 URL에 접속하지 않음");
    Row second=send("{{CALLBACK}}","다른 URL의 HTTP 콜백 재검증");CallbackProbe.Hit repeated=callbackHit(second);
    if(repeated!=null){note(second,"SSRF HTTP 콜백 재현 후보","서로 다른 고유 URL 2개에 GET 수신 · 출처 "+repeated.source()+" · 서버/중계/보안 장비 요청 여부 추가 확인");reason="HTTP 콜백 2회 재현 · 목록 변형 생략";}else reason="HTTP 콜백 1회 관찰 · 추가 검증 미완료";
   }else reason="대기 시간 내 HTTP 콜백 없음 · blind DNS/지연 콜백/다른 페이로드는 미검사";
   return result(unique.size(),0);
  }
  if(c.profile()==Profile.XSS&&available()){
   Row probe=send("{{MARKER}}","문자열 반사 탐색");used.add("{{MARKER}}");
   if(comparable(probe)&&!baseline.truncated()&&baseline.decoded()){
    String marker=probe.payload();boolean html=probe.response().mime().toLowerCase(Locale.ROOT).contains("text/html");
    if(!html||!probe.response().text().contains(marker)){
     reason=html?"현재 응답에 마커 없음 · 반사형 XSS 후속 검사 생략; stored/DOM/다른 화면은 미검사":"현재 응답이 HTML 아님 · HTML 반사 검사 생략; DOM/후속 HTML 소비는 미검사";
     return result(unique.size(),countUsed(plan,used));
    }
    String text=probe.response().text();int at=text.indexOf(marker);String before=text.substring(0,at).toLowerCase(Locale.ROOT);
    htmlTextContext=before.lastIndexOf('<')<before.lastIndexOf('>')&&before.lastIndexOf("<script")<=before.lastIndexOf("</script")&&before.lastIndexOf("<style")<=before.lastIndexOf("</style")&&before.lastIndexOf("<textarea")<=before.lastIndexOf("</textarea");
    String preferred=before.lastIndexOf("<textarea")>before.lastIndexOf("</textarea")?"</textarea>":before.lastIndexOf('"')>before.lastIndexOf('>')?"\">":before.lastIndexOf('\'')>before.lastIndexOf('>')?"'>":"<svg";
    plan.sort(Comparator.comparingInt(p->p.startsWith(preferred)?0:1));
    note(probe,"반사 위치 관찰","문자열 반사 확인 · 인접 태그/따옴표로 검사 순서 조정(휴리스틱); 실행 여부는 미확인");
   }
  }
  if(c.profile()==Profile.SQLI){
   // One quote probe first: fresh DB-specific errors may avoid unnecessary boolean families.
   if(unique.contains("'")&&available()){
    used.add("'");Row quote=send("'","SQL 구문 오류 탐색");
    if(quote!=null&&quote.verdict().equals("SQL 오류 후보")&&available()){
     Row repeat=send("'","SQL 오류 재검증"),control=send(c.baseline(),"SQL 오류 기준 재확인");
     if(repeat!=null&&repeat.verdict().equals("SQL 오류 후보")&&comparable(control)&&!SQL.matcher(control.response().text()).find()){
      note(repeat,"SQL 오류 후보 · 재현","DB 고유 오류 2회, 기준에는 없음 · 실제 인젝션 성공은 수동 확인 필요");reason="SQL 오류 재현 · 조건/변형 검사 생략";return result(unique.size(),countUsed(plan,used));
     }
    }
   }
   // Prefer paired semantics over quote errors; only use complete pairs from the user's list.
   List<String> truths=plan.stream().filter(p->p.contains("1=1")||p.contains("'1'='1'")).toList();
   for(String truth:truths){String falsity=truth.replace("1=1","1=2").replace("'1'='1'","'1'='2'");if(!unique.contains(falsity)||!available())continue;
    Row tr=send(truth,"SQL 참 조건"),fa=send(falsity,"SQL 거짓 조건");if(tr!=null)used.add(truth);if(fa!=null)used.add(falsity);
    if(sqlPair(tr,fa)&&available()){
     // Reverse order rejects transient/order-dependent differences. A control catches late session drift.
     Row fa2=send(falsity,"SQL 역순 거짓 재검증"),tr2=send(truth,"SQL 역순 참 재검증"),control=send(c.baseline(),"SQL 기준 재확인");
     if(sqlPair(tr2,fa2)&&fa.response().status()==fa2.response().status()&&comparable(control)&&norm(fa).equals(norm(fa2))&&norm(tr).equals(norm(tr2))&&control.response().status()==baseline.status()&&control.response().text().equals(baseline.text())){
      note(fa2,"SQL 조건 차이 재현 후보","참/거짓 상태·본문 차이 2회, 역순 재현, 기준 재확인 · WAF/일반 필터 가능성 포함, SQLi 확정 아님");reason="SQL 조건 차이 재현 · 나머지 변형 생략";return result(unique.size(),countUsed(plan,used));
     }
    }
   }
  }
  if(c.profile()==Profile.XPATH||c.profile()==Profile.LDAP||c.profile()==Profile.NOSQL){
   List<String[]> pairs=new ArrayList<>();
   if(c.profile()==Profile.XPATH)for(String t:plan)if(t.contains("'1'='1"))pairs.add(new String[]{t,t.replace("'1'='1","'1'='2")});
   if(c.profile()==Profile.NOSQL)for(String t:plan)if(t.contains("\"$ne\""))pairs.add(new String[]{t,t.replace("\"$ne\"","\"$eq\"")});
   if(c.profile()==Profile.LDAP)for(String t:plan)if(t.endsWith("*)(|(cn=*))"))pairs.add(new String[]{t,t.replace("*)(|(cn=*))","*)(cn=PR_LDAP_NONEXISTENT)")});
   for(String[] v:pairs){if(!unique.contains(v[1])||!available())continue;Row tr=send(v[0],"조건 대조: 넓은 조건"),fa=send(v[1],"조건 대조: 제한 조건");if(tr!=null)used.add(v[0]);if(fa!=null)used.add(v[1]);
    if(pair(tr,fa)&&available()){Row fa2=send(v[1],"역순 제한 조건"),tr2=send(v[0],"역순 넓은 조건"),control=send(c.baseline(),"조건 대조 기준 재확인");
     if(pair(tr2,fa2)&&norm(fa).equals(norm(fa2))&&norm(tr).equals(norm(tr2))&&comparable(control)&&control.response().status()==baseline.status()&&control.response().text().equals(baseline.text())){note(fa2,label(c.profile())+" 조건 차이 재현 후보","조건 차이 2회, 역순·기준 재확인 · 검색 기능/연산자 정상 지원 가능성 확인; 인젝션 확정 아님");reason="조건 차이 재현 · 나머지 변형 생략";return result(unique.size(),countUsed(plan,used));}
    }
   }
  }
  for(String p:plan){if(!available())break;if(used.contains(p))continue;
   // No marker expressions: compare fully expanded bytes before sending (e.g. duplicate literal payload).
   String key=expand(p,c.baseline(),"{{MARKER}}");if(used.contains(key))continue;used.add(p);used.add(key);
   if(key.equals(c.baseline()))continue;
   Row observation=send(p,"후보 탐색");
   if(c.profile()==Profile.XSS&&htmlTextContext&&comparable(observation)&&p.startsWith("<")&&encodedReflection(observation)&&plan.stream().allMatch(x->used.contains(x)||x.contains("<"))){
    note(observation,"메타문자 인코딩 관찰","HTML 텍스트 위치에서 전체 입력이 엔티티 인코딩됨 · 다른 컨텍스트/우회는 미검사");
    reason="HTML 텍스트의 인코딩 관찰 · 남은 정형 태그 변형 생략; 우회/stored/DOM 미검사 · 전체 목록 모드로 확대 가능";return result(unique.size(),countUsed(plan,used));
   }
   if(mutation&&options.verifyUpload()&&comparable(observation)&&available()&&verifyUpload(observation)){reason="업로드 내용 일치와 활성 MIME/inline 제공 관찰 · 나머지 변형 생략; 실행 여부 미확인";return result(unique.size(),countUsed(plan,used));}
   boolean custom=c.profile()==Profile.CUSTOM&&!c.proof().isBlank()&&observation!=null&&observation.verdict().equals("지정 응답 문자열 후보");
   if(!mutation&&c.profile()!=Profile.SSRF&&(evidence(observation)||custom)&&available()){
    String confirmation=p;
    if(c.profile()==Profile.SSTI&&p.contains("731*919"))confirmation=p.replace("731*919","731*920");
    if(c.profile()==Profile.REDIRECT){String h=hostOf(expand(p,c.baseline(),"PR_REDIRECT_PROBE"));if(!h.isBlank())confirmation=p.replaceAll("(?i)"+java.util.regex.Pattern.quote(h),"pr-second.invalid");}
    // Never resend uploaded files or rely on acceptance/strings as a vulnerability.
    Row repeat=send(confirmation,c.profile()==Profile.SSTI?"다른 산술식 재검증":"새 마커/동일 입력 재검증");if(repeat!=null&&unique.contains(confirmation))used.add(confirmation);
    Row control=send(c.baseline(),"후속 기준 재확인");
    boolean clean=control!=null&&control.response()!=null&&control.response().status()==baseline.status()&&control.response().decoded()&&!control.response().truncated()&&(!evidence(control));
    boolean reproduced=repeat!=null&&repeat.verdict().equals(observation.verdict())&&(evidence(repeat)||custom)&&clean;
    if(reproduced&&(c.profile()==Profile.DOWNLOAD||c.profile()==Profile.TRAVERSAL||c.profile()==Profile.XXE)){
     String negative=missingControl(p);if(negative!=null){Row missing=send(negative,"존재하지 않는 파일 대조");reproduced=missing!=null&&missing.response()!=null&&missing.response().decoded()&&!missing.response().truncated()&&!halted&&!evidence(missing);if(!reproduced&&missing!=null)note(missing,"파일 근거 판정 보류","없는 파일 입력에도 같은 근거/오류 발생 · 일반 응답/동적 응답 가능성");}
    }
    if(reproduced&&c.profile()==Profile.COMMAND&&p.contains("'PR_CMD_%s'")){
     Row other=send(p.replace("'PR_CMD_%s'","'PR_CTRL_%s'"),"서로 다른 출력 대조");String mark=marker(other);reproduced=other!=null&&comparable(other)&&!mark.isBlank()&&fresh(baseline,other.response(),other.payload(),"PR_CTRL_"+mark);
     if(other!=null)note(other,reproduced?"출력 대조 일치":"출력 대조 미완료","PR_CMD/PR_CTRL 서로 다른 조합 출력 확인 · 동일 입력문 반사는 제외");
    }
    if(reproduced){
     note(repeat,observation.verdict()+" · 재현", "같은 종류의 증거 2회 + 기준 재확인 · "+repeat.evidence()+" · 자동 확정 아님");
     reason="유형별 증거 재현 · 나머지 변형 생략";break;
    }
   }
  }
  if(mutation&&!halted&&!limited)reason=options.verifyUpload()?"업로드와 같은 대상의 반환 URL 재조회 · 실행/다른 계정 접근은 미검사":"업로드 목록 검사 · 저장/접근/실행 검증 없음; 자동 반복 재검증 제외";
  else if(c.profile()==Profile.SSRF&&!halted&&!limited)reason="SSRF 응답 관찰 완료 · blind SSRF는 콜백/수신 로그가 필요, 자동 확정 없음";
  return result(unique.size(),countUsed(plan,used));
 }
 String marker(Row row){if(row==null)return "";var m=java.util.regex.Pattern.compile("PR_[0-9a-f]{16}").matcher(row.payload());String last="";while(m.find())last=m.group();return last;}
 CallbackProbe.Hit callbackHit(Row row){String mark=marker(row);if(mark.isBlank()||options.callback()==null)return null;try{return options.callback().await(mark,options.callbackWaitMs(),stop);}catch(InterruptedException e){Thread.currentThread().interrupt();stop.set(true);return null;}}
 String missingControl(String p){
  if(c.profile()==Profile.XXE){var m=java.util.regex.Pattern.compile("SYSTEM\\s+[\"'](file:[^\"']+)[\"']").matcher(p);if(m.find())return p.substring(0,m.end(1))+".pr-missing-"+UUID.randomUUID()+p.substring(m.end(1));return null;}
  int slash=Math.max(p.lastIndexOf('/'),p.lastIndexOf('\\'));return p.substring(0,slash+1)+"PR_MISSING_"+UUID.randomUUID()+".txt";
 }
 boolean verifyUpload(Row upload){
  // Follow only a simple JSON relative URL on the same target. No arbitrary host or method.
  if(!upload.response().mime().toLowerCase(Locale.ROOT).contains("json"))return false;
  var m=java.util.regex.Pattern.compile("\"url\"\\s*:\\s*\"([^\"\\\\]{1,2000})\"").matcher(upload.response().text());if(!m.find())return false;String path=m.group(1);
  try{var u=new java.net.URI(path);if(!path.startsWith("/")||path.startsWith("//")||u.isAbsolute()||u.getRawAuthority()!=null||u.getFragment()!=null||path.contains(" ")||path.contains("\r")||path.contains("\n")){note(upload,"재조회 생략","같은 대상의 단순 상대 URL이 아님 · 자동 외부 이동 없음");return false;}}catch(Exception e){note(upload,"재조회 생략","반환 URL 해석 불가");return false;}
  String head=new String(upload.request(),0,headerEnd(upload.request()),java.nio.charset.StandardCharsets.ISO_8859_1);
  StringBuilder req=new StringBuilder("GET "+path+" HTTP/1.1\r\n");for(String line:head.split("\r\n"))if(line.matches("(?i)(Host|Cookie|Authorization|User-Agent):.*"))req.append(line).append("\r\n");req.append("Connection: close\r\nAccept: */*\r\n\r\n");
  Row fetched=exchange(req.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),path,"","업로드 반환 URL 재조회",false);if(fetched==null)return false;
  byte[] body=Arrays.copyOfRange(upload.request(),headerEnd(upload.request())+4,upload.request().length);String expected=new String(body,java.nio.charset.StandardCharsets.UTF_8);
  boolean exact=body.length>0&&body.length<=RETAIN&&Arrays.equals(body,expected.getBytes(java.nio.charset.StandardCharsets.UTF_8))&&comparable(fetched)&&fetched.response().text().equals(expected);
  if(!exact){note(fetched,"업로드 재조회 미확인","반환 파일이 원래 UTF-8 내용과 일치하지 않음/차단/잘림 · 저장 실패 확정 아님");return false;}
  String mime=fetched.response().mime().toLowerCase(Locale.ROOT),disposition=header(responseHeaders(fetched.response()),"Content-Disposition").toLowerCase(Locale.ROOT);
  boolean active=(mime.contains("text/html")||mime.contains("image/svg+xml")||mime.contains("application/xhtml+xml"))&&!disposition.startsWith("attachment")&&java.util.regex.Pattern.compile("(?i)<(?:html|svg|script|img|b|!doctype)\\b").matcher(expected).find();
  note(fetched,active?"업로드 활성 콘텐츠 제공 후보":"업로드 파일 재조회 확인","업로드 응답의 상대 URL에서 내용 일치 · MIME="+mime+" · Content-Disposition="+disposition+" · 브라우저 실행/다른 계정 접근/서버 실행은 미검사");
  return active;
 }
 static boolean encodedReflection(Row r){
  String p=r.payload();if(!p.contains("<"))return false;
  String escaped=p.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
  for(String entity:new String[]{"&#39;","&#x27;","&apos;"})if(r.response().text().contains(escaped.replace("'",entity)))return true;
  return false;
 }
 int countUsed(List<String> plan,Set<String> used){return (int)plan.stream().filter(used::contains).count();}
 Result result(int unique,int tested){return new Result(sent,unique,Math.max(0,c.payloads().size()-tested),stop.get()||halted,limited,reason);}
}
