package kr.workbench;

import kr.payloadrunner.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Opt-in identity controls. Never resumes payload requests after an access-denial status. */
public final class PayloadSessionCheck {
  public record Config(String path,String identityPath,String expectedIdentity) {
    public Config {
      if(path==null||path.length()>2048||path.chars().anyMatch(c->c<=32||c==127))throw new IllegalArgumentException("신원 확인 GET 경로를 입력하세요.");
      URI uri;try{uri=URI.create(path);}catch(Exception ex){throw new IllegalArgumentException("신원 확인 경로 형식 오류");}
      if(!path.startsWith("/")||path.startsWith("//")||uri.isAbsolute()||uri.getRawAuthority()!=null||uri.getFragment()!=null||path.indexOf('\\')>=0)
        throw new IllegalArgumentException("신원 확인은 같은 대상의 /api/me 형식 GET 경로만 지원합니다.");
      if(identityPath==null||identityPath.length()>256||!identityPath.matches("\\$\\.[A-Za-z_][A-Za-z0-9_-]*(?:\\.[A-Za-z_][A-Za-z0-9_-]*){0,7}")||expectedIdentity==null||expectedIdentity.isBlank()||expectedIdentity.length()>256||expectedIdentity.chars().anyMatch(c->c<32))
        throw new IllegalArgumentException("신원 JSON 경로와 기대 사용자 값을 입력하세요. 예: $.user.id / alice");
    }
    @Override public String toString(){return "PayloadSessionCheck[configured]";}
  }
  public record Result(int requests,int completed,boolean stopped,boolean budgetReached,String reason) {}
  enum Identity { MATCH, DIFFERENT, UNKNOWN }
  private PayloadSessionCheck() {}
  static byte[] request(byte[] baseline,String path,boolean anonymous){
    String raw=new String(baseline,StandardCharsets.ISO_8859_1);int boundary=raw.indexOf("\r\n\r\n");if(boundary<0)throw new IllegalArgumentException("요청 헤더 경계 없음");
    StringBuilder result=new StringBuilder("GET "+path+" HTTP/1.1\r\n");int hosts=0;
    String[] lines=raw.substring(0,boundary).split("\r\n");
    for(int i=1;i<lines.length;i++){String line=lines[i];
      int split=line.indexOf(':');if(split<1)continue;String key=line.substring(0,split).toLowerCase(Locale.ROOT);
      if(key.equals("host"))hosts++;
      if(anonymous&&!Set.of("host","accept","user-agent").contains(key))continue;
      if(Set.of("content-length","content-type","content-encoding","transfer-encoding","connection","expect","upgrade","proxy-authorization").contains(key))continue;
      result.append(line).append("\r\n");
    }
    if(hosts!=1)throw new IllegalArgumentException("신원 확인에는 Host 헤더 한 개가 필요합니다.");
    return result.append("Connection: close\r\n\r\n").toString().getBytes(StandardCharsets.ISO_8859_1);
  }
  static boolean matches(Config check,Engine.Response response){
    return response!=null&&response.status()>=200&&response.status()<300&&identityValue(check,response);
  }
  static boolean identityValue(Config check,Engine.Response response){
    return identity(check,response)==Identity.MATCH;
  }
  static Identity identity(Config check,Engine.Response response){
    if(response==null||!response.decoded()||response.truncated()||!response.mime().toLowerCase(Locale.ROOT).contains("json"))return Identity.UNKNOWN;
    try{
      Object value=MinerJson.parse(response.text(),128*1024,6000);
      if(!(value instanceof Map<?,?>))return Identity.UNKNOWN;
      for(String part:check.identityPath().substring(2).split("\\.")){
        if(value==null)return Identity.DIFFERENT;
        if(!(value instanceof Map<?,?> map))return Identity.UNKNOWN;
        if(!map.containsKey(part))return Identity.DIFFERENT;
        value=map.get(part);
      }
      if(value==null)return Identity.DIFFERENT;
      if(!(value instanceof String||value instanceof Number))return Identity.UNKNOWN;
      return check.expectedIdentity().equals(value.toString())?Identity.MATCH:Identity.DIFFERENT;
    }catch(IllegalArgumentException ex){return Identity.UNKNOWN;}
  }
  static boolean negativeSuitable(Config check,Engine.Response response){
    return response!=null&&(response.status()==401||response.status()==403||response.status()>=200&&response.status()<300)
      &&identity(check,response)==Identity.DIFFERENT;
  }
  public static Result run(Engine.Config config,Engine.Transport transport,AtomicBoolean stop,Consumer<Engine.Row> receive,Config check,boolean adaptive,Adaptive.Options options)throws Exception {
    byte[] original=Engine.render(config.template(),config.baseline(),config.encoding());
    byte[] authenticated=request(original,check.path(),false),anonymous=request(original,check.path(),true);
    int sent=0;
    for(int phase=0;phase<2;phase++){
      if(stop.get())return new Result(sent,0,true,false,"신원 대조 중지 · 검사값 미전송");
      if(sent>=config.budget())return new Result(sent,0,true,true,"신원 대조 요청 한도 · 검사값 미전송");
      if(sent>0)pause(config.intervalMs(),stop);if(stop.get())return new Result(sent,0,true,false,"신원 대조 중지 · 검사값 미전송");
      byte[] req=phase==0?authenticated:anonymous;Engine.Response response;
      sent++;try{response=Engine.parse(transport.send(req));}catch(Exception ex){if(ex instanceof InterruptedException){Thread.currentThread().interrupt();stop.set(true);}return new Result(sent,0,true,false,"신원 대조 전송/분석 실패 · 검사값 미전송");}
      Identity observed=identity(check,response);boolean match=response.status()>=200&&response.status()<300&&observed==Identity.MATCH;
      String observation=switch(observed){case MATCH->"설정한 신원 일치";case DIFFERENT->"설정한 신원 불일치";case UNKNOWN->"신원 미확인 · 응답 형식/크기/디코딩 한도";};
      receive.accept(new Engine.Row(-10-phase,"[신원 대조]",0,response,phase==0?"검사 전 신원 확인":"비로그인 신원 대조","HTTP "+response.status()+" · "+observation+" · 검사값 전송과 별도",req));
      if(phase==0&&!match)return new Result(sent,0,true,false,"검사 전 신원 미확인 · 세션 만료/인증 설정/응답 형식 확인 · 검사값 미전송");
      if(phase==1&&!negativeSuitable(check,response))
        return new Result(sent,0,true,false,observed==Identity.MATCH?"비로그인에도 같은 신원 반환 · 신원 확인 API 부적합 · 검사값 미전송":"비로그인 신원 대조 불완전 · 검사값 미전송");
    }
    if(sent>=config.budget())return new Result(sent,0,true,true,"신원 대조 완료 · 검사 요청 예산 없음");
    pause(config.intervalMs(),stop);if(stop.get())return new Result(sent,0,true,false,"신원 대조 후 중지 · 검사값 미전송");
    var bounded=new Engine.Config(config.profile(),config.encoding(),config.template(),config.baseline(),config.payloads(),config.intervalMs(),config.budget()-sent,config.proof());
    AtomicBoolean denied=new AtomicBoolean();Consumer<Engine.Row> sink=row->{if(row.response()!=null&&(row.response().status()==401||row.response().status()==403))denied.set(true);receive.accept(row);};
    boolean stopped,limited;int completed;String reason;
    if(adaptive){var result=Adaptive.run(bounded,transport,stop,sink,options);sent+=result.requests();completed=result.uniquePayloads();stopped=result.stopped();limited=result.budgetReached();reason=result.reason();}
    else {var result=Engine.run(bounded,transport,stop,sink);sent+=result.requests();completed=result.completed();stopped=result.stopped();limited=result.budgetReached();reason=result.reason();}
    if(denied.get()){
      reason="HTTP 401/403 · 검사값 후속 요청 중단";
      if(stop.get())return new Result(sent,completed,true,limited,reason+" · 신원 재확인 취소");
      if(sent>=config.budget())return new Result(sent,completed,true,true,reason+" · 신원 재확인 예산 없음 · 원인 미확정");
      pause(config.intervalMs(),stop);if(stop.get())return new Result(sent,completed,true,limited,reason+" · 신원 재확인 취소");
      sent++;try{
        Engine.Response after=Engine.parse(transport.send(authenticated));boolean match=matches(check,after);
        String evidence=match?"검사 전·후 같은 신원 일치, 비로그인 불일치 · 세션 유효 · 대상별 권한/CSRF/업무 오류는 미확정":"검사 전 신원 일치 → 검사 후 신원 미확인 · 세션 변화/만료 또는 확인 API 오류 가능";
        receive.accept(new Engine.Row(-12,"[신원 재확인]",0,after,match?"세션 유효 · 대상 응답 보류":"세션 상태 변화 · 판정 보류",evidence+" · 검사값 후속 요청 없음",authenticated));
        reason+=" · "+evidence;
      }catch(Exception ex){if(ex instanceof InterruptedException){Thread.currentThread().interrupt();stop.set(true);}reason+=" · 신원 재확인 실패 · 세션 상태/업무 오류 미확정";}
      stopped=true;
    }
    return new Result(sent,completed,stopped,limited,reason);
  }
  private static void pause(int millis,AtomicBoolean stop)throws InterruptedException {for(int n=millis;n>0&&!stop.get();n-=Math.min(100,n))Thread.sleep(Math.min(100,n));}
}
