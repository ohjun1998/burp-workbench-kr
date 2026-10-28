package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.*;

/** Bounded, resumable GET worker. Swing owns the presentation and durable metadata. */
final class EnvironmentScan {
  record Profile(String account,Map<String,String> headers,CookieSessionProbe.Config check){Profile{headers=Map.copyOf(headers);}}
  record Options(boolean compare,boolean repeat,Set<String> ignored,int delay,int timeout,int budget,int depth,boolean follow,boolean verify,String app,List<String> prefixes){Options{ignored=Set.copyOf(ignored);prefixes=List.copyOf(prefixes);if(delay<0||delay>10000||timeout<1000||timeout>30000||budget<1||budget>500||depth<0||depth>3)throw new IllegalArgumentException("요청 설정 범위 오류");}}
  record Result(String url,String request,String response,String decoded,String state,int status,
      EnvironmentContent.Finding finding,String fingerprint,EnvironmentCatalog.Site learned) {}
  final MontoyaApi api;final Options options;final Consumer<Result> sink;final BooleanSupplier closed;
  final ConcurrentLinkedDeque<EnvironmentProject.Pending> queue=new ConcurrentLinkedDeque<>();
  final Set<String> seen=new HashSet<>();final Map<String,EnvironmentControls.Reference> references=new HashMap<>();
  volatile Profile profile;volatile boolean paused,stopped;volatile String note="";volatile long retryAt;
  volatile boolean resetReferences;
  volatile int sent,completed;long lastSend;Profile verified;int verifiedAt=-100;
  private final Object signal=new Object();
  static final class Halt extends RuntimeException{Halt(String s){super(s);}}
  static final class Retry extends RuntimeException{}
  EnvironmentScan(MontoyaApi api,Options options,Profile profile,List<EnvironmentProject.Pending> tasks,Consumer<Result> sink,BooleanSupplier closed){
    this.api=api;this.options=options;this.profile=profile;this.sink=sink;this.closed=closed;
    if(tasks.size()>500)throw new IllegalArgumentException("대기 경로 최대 500개");
    String origin=EnvironmentCatalog.origin(URI.create(tasks.get(0).url()));
    if(profile.check!=null&&!origin.equals(EnvironmentCatalog.origin(URI.create(profile.check.url()))))throw new IllegalArgumentException("신원 검사와 대상 Origin 불일치");
    for(var task:tasks){if(!EnvironmentCatalog.origin(URI.create(task.url())).equals(origin))throw new IllegalArgumentException("한 실행은 같은 Origin의 경로만 검사합니다.");if(seen.add(task.url()))queue.add(task);}
  }
  void guard(){if(stopped||closed.getAsBoolean()||Thread.currentThread().isInterrupted())throw new Halt("검증 중지");}
  void pause(String reason,long until){synchronized(signal){note=reason;retryAt=until;paused=true;}}
  void stop(){stopped=true;synchronized(signal){signal.notifyAll();}}
  void resume(Profile next){synchronized(signal){
    if(!next.account.equals(profile.account)||!Objects.equals(next.check,profile.check))throw new IllegalArgumentException("동일 계정·신원 조건으로 재개하세요. 다른 계정은 새 검사를 시작하세요.");
    if(System.currentTimeMillis()<retryAt)throw new IllegalArgumentException("요청 제한 대기 중 · "+((retryAt-System.currentTimeMillis()+999)/1000)+"초 후 재개 가능");
    profile=next;verified=null;resetReferences=true;paused=false;note="재개 · 세션 재확인";signal.notifyAll();
  }}
  void ready(){synchronized(signal){while(paused){guard();try{signal.wait(100);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Halt("검증 중지");}}}guard();}
  void beforeSend(){
    ready();if(sent>=options.budget)throw new Halt("요청 한도 도달 · 남은 경로 이어서 가능");
    long remaining=options.delay-(System.currentTimeMillis()-lastSend);
    while(remaining>0){try{Thread.sleep(Math.min(100,remaining));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Halt("검증 중지");}ready();remaining=options.delay-(System.currentTimeMillis()-lastSend);}
    guard();sent++;lastSend=System.currentTimeMillis();
  }
  Set<String> names(Profile p){Set<String> names=new HashSet<>(AuthComparePanel.DEFAULT_AUTH);names.addAll(AuthComparePanel.CSRF);names.addAll(p.headers.keySet());return names;}
  void identity(){
    ready();if(profile.headers.isEmpty()||!options.verify)return;
    if(verified==profile&&sent-verifiedAt<25)return;
    Profile captured=profile;
    if(captured.check==null)throw new Halt("세션 검사 조건 없음 · 권한 비교에서 세션 검사를 적용하세요.");
    try{CookieSessionProbe.verify(api,captured.check,captured.headers,names(captured),AuthComparePanel.origin(HttpRequest.httpRequestFromUrl(captured.check.url())),()->stopped||closed.getAsBoolean()||profile!=captured,()->{beforeSend();if(profile!=captured)throw new Retry();});
      if(profile!=captured)throw new Retry();verified=captured;verifiedAt=sent;
    }catch(Exception e){if(stopped||closed.getAsBoolean()||sent>=options.budget)throw new Halt(sent>=options.budget?"요청 한도 도달":"검증 중지");if(profile!=captured)throw new Retry();pause("세션 확인 실패 · 세션 갱신 후 재개",0);throw new Retry();}
  }
  HttpRequestResponse send(String url){
    ready();Profile captured=profile;identity();if(profile!=captured)throw new Retry();
    HttpRequest q=Core.sessionRequest(HttpRequest.httpRequestFromUrl(url),captured.headers,names(captured));
    if(!WorkbenchScope.contains(api,q))throw new Halt("SCOPE 제외 · 범위 복구 후 남은 경로 재개");
    beforeSend();if(profile!=captured||options.verify&&!captured.headers.isEmpty()&&verified!=captured)throw new Retry();
    if(!WorkbenchScope.contains(api,q))throw new Halt("SCOPE 제외 · 범위 복구 후 남은 경로 재개");
    HttpRequestResponse rr=api.http().sendRequest(q,RequestOptions.requestOptions().withResponseTimeout(options.timeout).withRedirectionMode(RedirectionMode.NEVER));
    if(profile!=captured)throw new Retry();
    if(rr==null||rr.response()==null)throw new IllegalArgumentException("응답 없음");
    if(rr.request()==null||!AuthComparePanel.sameTarget(rr.request(),q)||!WorkbenchScope.contains(api,rr.request())||!AuthComparePanel.credentialValues(rr.request(),names(captured)).equals(AuthComparePanel.credentialValues(q,names(captured))))throw new IllegalArgumentException("실제 전송 대상/인증 변경 · 판정 보류");
    if(!captured.headers.isEmpty())for(var header:rr.response().headers()==null?List.<burp.api.montoya.http.message.HttpHeader>of():rr.response().headers())if(header.name().equalsIgnoreCase("set-cookie")){
      String pair=header.value().split(";",2)[0];int split=pair.indexOf('=');var cookies=CookieSessionProbe.cookies(captured.headers);
      if(split>0&&cookies.containsKey(pair.substring(0,split).strip())&&(!cookies.get(pair.substring(0,split).strip()).equals(pair.substring(split+1).strip())||header.value().matches("(?i).*;\\s*max-age=\\s*0(?:;.*)?"))){pause("세션 쿠키 갱신 · 새 인증정보 연결 후 재개",0);throw new Retry();}
    }
    return rr;
  }
  String root(String url){URI u=URI.create(url);String app=options.app;String path=u.getPath();if(!app.isEmpty()&&!path.equals(app)&&!path.startsWith(app+"/"))app="";
    if(app.isEmpty())for(String p:options.prefixes)if((path.equals(p)||path.startsWith(p+"/"))&&p.length()>app.length())app=p;
    return EnvironmentCatalog.origin(u)+app;
  }
  EnvironmentControls.Reference reference(String url){
    String root=root(url);if(!options.compare)return null;if(references.containsKey(root))return references.get(root);
    EnvironmentControls.Sample first=null,second=null;String issue="기준 요청 실패";
    for(int i=0;i<(options.repeat?2:1);i++){
      String missing=root+"/wb-missing-"+UUID.randomUUID();
      try{var rr=send(missing);var r=rr.response();String h=EnvironmentPanel.responseHeaders(r),body=EnvironmentPanel.decodedBody(PacketFormat.decodedResponse(r));
        if(r.statusCode()==429){pause("기준 HTTP 429 · 대기 후 재개",retryTime(h));throw new Retry();}
        if(EnvironmentContent.challenge(body)){pause("기준 보안 확인 화면 · 확인 후 재개",0);throw new Retry();}
        var sample=EnvironmentControls.sample(r.statusCode(),h,body,missing,options.ignored);if(i==0)first=sample;else second=sample;
      }catch(Retry|Halt e){throw e;}catch(Exception e){issue="기준 요청/SCOPE/디코딩 오류";break;}
    }
    var ref=EnvironmentControls.reference(first,second,options.repeat,issue);references.put(root,ref);return ref;
  }
  void run(){
    try{while(!queue.isEmpty()){
      ready();if(resetReferences){references.clear();resetReferences=false;}var task=queue.peek();String url=task.url();
      try{
        identity();var ref=reference(url);var rr=send(url);var response=rr.response();int status=response.statusCode();
        String headers=EnvironmentPanel.responseHeaders(response),decoded=PacketFormat.decodedResponse(response),body=EnvironmentPanel.decodedBody(decoded);
        String comparison=EnvironmentControls.state(status,headers,body,url,ref,options.ignored);
        var finding=EnvironmentContent.inspect(url,status,headers,body,comparison);
        boolean fallback=Set.of("SOFT404","SIMILAR","LOGIN","CHALLENGE","LIMIT").contains(finding.kind());
        EnvironmentCatalog.Site learned=null;
        if(!fallback&&status>=200&&status<300){learned=new EnvironmentCatalog().observe(url,headers,body);if(options.follow&&task.depth()<options.depth)follow(learned,task.depth()+1);}
        var sample=EnvironmentControls.sample(status,headers,body,url,options.ignored);
        String digest=sample.signature().isEmpty()?"":EnvironmentProject.hash(sample.signature().startsWith("TEXT:")?EnvironmentContent.normalize(sample.signature()):sample.signature());
        String state="HTTP "+status+" · "+finding.title()+" · "+comparison.replaceFirst("^HTTP \\d{3} · ","");if(!options.verify&&!profile.headers.isEmpty())state+=" · 신원 미검증";
        sink.accept(new Result(url,EnvironmentPanel.preview(rr.request().toString()),EnvironmentPanel.preview(response.toString()),EnvironmentPanel.preview(decoded),state,status,finding,digest,learned));completed++;
        if(status==429){pause("HTTP 429 · 대기 후 재개",retryTime(headers));continue;}
        if(finding.kind().equals("CHALLENGE")){pause("보안 확인 화면 · 확인 후 재개",0);continue;}
        queue.poll();if(!profile.headers.isEmpty()&&(status==401||finding.kind().equals("LOGIN")))verified=null;
      }catch(Retry retry){continue;}catch(Halt halt){throw halt;}catch(Exception error){
        sink.accept(new Result(url,"GET "+url,"요청 실패 · "+error.getClass().getSimpleName(),"요청 실패 · "+error.getClass().getSimpleName(),"전송 오류 · 판정 보류",0,new EnvironmentContent.Finding("ERROR","전송 오류","응답/SCOPE/실제 전송 조건 확인 실패","원본과 Burp 규칙을 확인하세요."),"",null));completed++;queue.poll();
      }
    }note="검증 완료";}catch(Halt halted){note=halted.getMessage();}
  }
  void follow(EnvironmentCatalog.Site learned,int depth){
    LinkedHashSet<String> found=new LinkedHashSet<>(learned.discovered.keySet());for(String path:learned.observed)found.add(learned.origin+path);
    for(String url:found){if(seen.size()>=500)break;if(seen.contains(url)||EnvironmentKnowledge.manualOnly(url)||url.matches("(?i).*/(?:logout|signout|delete|remove|shutdown|restart)(?:/.*)?$"))continue;
      try{new EnvironmentProject.Pending(url,depth);if(!WorkbenchScope.contains(api,HttpRequest.httpRequestFromUrl(url)))continue;seen.add(url);queue.add(new EnvironmentProject.Pending(url,depth));}catch(Exception ignored){}
    }
  }
  List<EnvironmentProject.Pending> remaining(){return List.copyOf(queue);}
  static long retryTime(String headers){
    String value=EnvironmentResponse.header(headers,"Retry-After").strip();long now=System.currentTimeMillis();
    if(value.matches("[0-9]+")){
      if(value.length()>18)return Long.MAX_VALUE;
      long seconds=Long.parseLong(value);return seconds>(Long.MAX_VALUE-now)/1000?Long.MAX_VALUE:now+seconds*1000;
    }
    try{return Math.max(now,ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli());}catch(Exception ignored){return now+1000;}
  }
}
