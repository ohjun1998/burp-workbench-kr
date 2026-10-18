package kr.workbench;

import java.net.URI;
import java.util.*;
import java.util.function.BooleanSupplier;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

/** Explicit bounded read/write/read observation. Never repeats or rolls back a write. */
final class AuthEffectCheck {
  enum Mode{EQUALS,CHANGED,ABSENT}
  enum Outcome{OBSERVED,UNCHANGED,UNEXPECTED,ALREADY,UNKNOWN}
  record Config(boolean enabled,String method,String path,String url,String jsonPath,String expected,Mode mode,int attempts){
    Config{if(enabled){if(!Set.of("POST","PUT","PATCH","DELETE").contains(method)||path==null||path.length()>512||!path.startsWith("/"))throw new IllegalArgumentException("처리 결과 대상 메서드·경로 확인");AuthPolicy.parse(method+" "+path+" PUBLIC",AuthPolicy.Access.UNSPECIFIED);URI uri;try{uri=URI.create(url);}catch(Exception ex){throw new IllegalArgumentException("후속 조회 URL 형식 확인");}if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||url.length()>2048)throw new IllegalArgumentException("후속 조회는 같은 Origin/SCOPE의 GET URL이어야 합니다.");if(mode==null||attempts<1||attempts>3||jsonPath==null||jsonPath.length()>256||(!jsonPath.isBlank()&&!jsonPath.startsWith("$."))||(mode!=Mode.ABSENT&&jsonPath.isBlank())||expected==null||expected.length()>256)throw new IllegalArgumentException("처리 결과 JSON 경로·기대값·조회 횟수 확인");}}
    static Config disabled(){return new Config(false,"POST","/","","","",Mode.CHANGED,1);}
    boolean matches(HttpRequest request){return enabled&&method.equals(request.method())&&AuthPolicy.parse(method+" "+path+" PUBLIC",AuthPolicy.Access.UNSPECIFIED).forRequest(request.method(),request.url())==AuthPolicy.Access.PUBLIC;}
    @Override public String toString(){return "EffectCheck["+(enabled?method+" "+path:"OFF")+"]";}
  }
  record Snapshot(HttpRequest request,HttpResponse response,boolean present,Object value){}
  record Evidence(Config config,Snapshot before,Snapshot after,Outcome outcome,String note){
    long bytes(){return before.response().body().length()+(after==null?0:after.response().body().length());}
  }
  interface Reader{HttpResponse read(HttpRequest request)throws Exception;}
  static HttpRequest observer(Config config,HttpRequest original,Set<String> names){
    HttpRequest read=HttpRequest.httpRequestFromUrl(config.url());Map<String,String> headers=new LinkedHashMap<>();for(var h:original.headers())if(names.contains(h.name().toLowerCase(Locale.ROOT)))headers.put(h.name().toLowerCase(Locale.ROOT),h.value());
    if(read==null||!"GET".equals(read.method())||!AuthComparePanel.origin(read).equals(AuthComparePanel.origin(original)))throw new IllegalArgumentException("후속 조회 Origin/GET 확인");
    for(var parameter:read.parameters())if(Set.of("access_token","token","session_id","sessionid","jwt").contains(parameter.name().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("후속 조회 URL에 인증값을 넣을 수 없습니다.");
    return Core.sessionRequest(read,headers,names);
  }
  static Snapshot snapshot(Config config,HttpRequest request,HttpResponse response)throws Exception{
    if(response.body().length()>128*1024)throw new IllegalArgumentException("처리 결과 응답 128 KiB 초과");
    int status=response.statusCode();if(status==404||status==410)return new Snapshot(request,response,false,null);
    if(status<200||status>=300)throw new IllegalArgumentException("처리 결과 조회 HTTP "+status);
    if(config.jsonPath().isBlank())return new Snapshot(request,response,true,AuthFileEvidence.digest(response));
    var json=AuthJudgement.json(AuthEvidencePanel.readBody(response));if(json==null)throw new IllegalArgumentException("처리 결과 JSON 구조 확인");String path=config.jsonPath();return new Snapshot(request,response,json.containsKey(path),json.get(path));
  }
  static boolean desired(Config config,Snapshot snapshot){return switch(config.mode()){
    case ABSENT->!snapshot.present();case CHANGED->false;case EQUALS->snapshot.present()&&Objects.equals(snapshot.value(),expected(config.expected()));
  };}
  static Object expected(String value){try{return MinerJson.parse(value,1024,32);}catch(Exception ex){return value;}}
  static Snapshot before(Config config,HttpRequest observer,Reader reader)throws Exception{
    var before=snapshot(config,observer,reader.read(observer));if(!before.present())throw new IllegalArgumentException("조회 대상·필드가 이미 없음 · 대상 요청 미전송");if(config.mode()==Mode.EQUALS&&desired(config,before))throw new IllegalArgumentException("조회 값이 이미 기대 상태 · 대상 요청 미전송 · 테스트 상태 초기화 필요");return before;
  }
  static Evidence after(Config config,Snapshot before,Reader reader,BooleanSupplier cancelled)throws Exception{
    Snapshot after=null;boolean unexpected=false;for(int i=0;i<config.attempts();i++){
      if(cancelled.getAsBoolean())throw new IllegalArgumentException("후속 조회 중지");if(i>0)Thread.sleep(500);if(cancelled.getAsBoolean())throw new IllegalArgumentException("후속 조회 중지");
      after=snapshot(config,before.request(),reader.read(before.request()));
      boolean changed=before.present()!=after.present()||!Objects.equals(before.value(),after.value());
      if(changed&&(config.mode()==Mode.CHANGED||desired(config,after)))return new Evidence(config,before,after,Outcome.OBSERVED,"후속 조회에서 기대 상태 변경 관측 · 요청과의 인과관계는 재현 근거로 확인");
      unexpected|=changed;
    }
    return new Evidence(config,before,after,unexpected?Outcome.UNEXPECTED:Outcome.UNCHANGED,unexpected?"조회 값 변경은 관측했으나 설정한 기대 상태에 도달하지 않음":"지정한 조회 항목의 변경 없음 · 조회 "+config.attempts()+"회 · 관측 범위와 지연 처리 확인");
  }
  static AuthFinding finding(Evidence evidence,BountyMatrix.Expected expected,AuthFinding previous){
    return finding(evidence,expected,previous,null);
  }
  static AuthFinding finding(Evidence evidence,BountyMatrix.Expected expected,AuthFinding previous,HttpResponse response){
    if(previous.kind()==AuthFinding.Kind.CHANGED||previous.kind()==AuthFinding.Kind.BASELINE||previous.kind()==AuthFinding.Kind.CONFLICT||previous.kind()==AuthFinding.Kind.REFERENCE)return previous;
    if(evidence.outcome()==Outcome.OBSERVED)return new AuthFinding(expected==BountyMatrix.Expected.DENY?AuthFinding.Kind.SUSPECT:expected==BountyMatrix.Expected.ALLOW?AuthFinding.Kind.ALLOWED:AuthFinding.Kind.REVIEW,"기대 "+expected+" · "+evidence.note(),previous.changes());
    if(evidence.outcome()==Outcome.UNEXPECTED)return new AuthFinding(AuthFinding.Kind.WRITE_PARTIAL,evidence.note()+" · 부분 처리/다른 변경 수동 확인",previous.changes());
    if(evidence.outcome()!=Outcome.UNCHANGED||evidence.after()==null)return new AuthFinding(AuthFinding.Kind.WRITE_READ_FAILED,"후속 조회 불완전 · 처리 여부 확인 불가",previous.changes());
    if(response!=null&&response.statusCode()==202)return new AuthFinding(AuthFinding.Kind.WRITE_PENDING,"HTTP 202 접수 · "+evidence.note()+" · 완료 결과를 별도로 확인",previous.changes());
    if(previous.kind()==AuthFinding.Kind.DENIAL_DATA)return previous;
    if(expected==BountyMatrix.Expected.DENY&&previous.kind()==AuthFinding.Kind.BLOCKED)return new AuthFinding(AuthFinding.Kind.BLOCKED,"기대 DENY · "+previous.reason()+" · "+evidence.note()+" · 전체 부작용 부재를 확정하지 않음",previous.changes());
    return new AuthFinding(AuthFinding.Kind.WRITE_UNCHANGED,"기대 "+expected+" · "+evidence.note()+" · 명시적 차단 근거 또는 업무 결과 추가 확인",previous.changes());
  }
}
