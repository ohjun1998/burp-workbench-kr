package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.net.URI;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Explicit, bounded session experiments. Only names and outcomes leave the runner. */
final class CookieSessionProbe {
  static final int MAX_COOKIES=32, MAX_REQUESTS=128, MAX_RESPONSE=128*1024;
  private static final class Failure extends IllegalArgumentException {
    Failure(String message){super(message);}
  }
  record Claim(String label,String path,String expected){Claim{if(label==null||label.length()>32||path==null||!path.startsWith("$.")||path.length()>256||expected==null||expected.isBlank()||expected.length()>256||expected.chars().anyMatch(c->c<32))throw new Failure("추가 신원 조건의 JSON 경로와 기대값을 확인하세요.");}}
  record Config(String url,String identityPath,String expectedIdentity,List<Claim> claims) {
    Config(String url,String identityPath,String expectedIdentity){this(url,identityPath,expectedIdentity,List.of());}
    Config {
      claims=List.copyOf(claims);if(claims.size()>8)throw new Failure("추가 신원 조건 최대 8개");
      if(url==null||url.length()>2048||url.chars().anyMatch(c->c<=32||c==127))
        throw new Failure("올바른 검증 URL을 입력하세요.");
      URI uri;
      try {uri=URI.create(url);}catch(Exception e){throw new Failure("검증 URL 형식 오류");}
      if(!Set.of("http","https").contains(Objects.toString(uri.getScheme(),"").toLowerCase(Locale.ROOT))
          ||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)
        throw new Failure("검증 URL은 사용자 정보·조각 없는 HTTP(S) 주소여야 합니다.");
      if(identityPath==null||!identityPath.startsWith("$.")||identityPath.length()>256
          ||identityPath.chars().anyMatch(c->c<32)||expectedIdentity==null||expectedIdentity.isBlank()
          ||expectedIdentity.length()>256||expectedIdentity.chars().anyMatch(c->c<32))
        throw new Failure("사용자 JSON 경로와 기대 사용자 값을 입력하세요.");
    }
    @Override public String toString(){return "SessionCheck["+Core.safeUrl(url)+", identity configured]";}
  }
  enum Outcome { MATCH, DIFFERENT, UNKNOWN }
  record Trial(String label,List<String> cookies,int status,Outcome outcome,String note,boolean identityMatched) {
    Trial(String label,List<String> cookies,int status,Outcome outcome,String note){this(label,cookies,status,outcome,note,outcome==Outcome.MATCH);}
    Trial {cookies=List.copyOf(cookies);}
  }
  record Finding(String name,String excluded,String alone,String result) {}
  record Report(boolean valid,boolean complete,String summary,List<Finding> findings,
      List<Trial> trials,Set<String> retained,boolean cookieOnly) {
    Report {findings=List.copyOf(findings);trials=List.copyOf(trials);retained=Set.copyOf(retained);}
  }

  static LinkedHashMap<String,String> cookies(Map<String,String> headers) {
    LinkedHashMap<String,String> cookies=new LinkedHashMap<>();
    String raw=headers.getOrDefault("cookie","");
    if(raw.length()>16384)throw new Failure("쿠키 헤더 16 KiB 제한");
    if(raw.isBlank())return cookies;
    for(String pair:raw.split(";",-1)) {
      int split=pair.indexOf('=');
      if(split<=0)throw new Failure("쿠키 이름=값 형식을 확인하세요.");
      String name=pair.substring(0,split).strip(),value=pair.substring(split+1).strip();
      String octets=value.startsWith("\"")&&value.endsWith("\"")&&value.length()>=2?value.substring(1,value.length()-1):value;
      if(!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")||octets.chars().anyMatch(c->c<33||c>=127||c=='\"'||c==','||c=='\\')
          ||cookies.putIfAbsent(name,value)!=null)
        throw new Failure("중복 이름 또는 잘못된 쿠키 형식 · 다시 수집하세요.");
      if(cookies.size()>MAX_COOKIES)throw new Failure("한 번에 쿠키 최대 32개를 검사합니다.");
    }
    return cookies;
  }
  static Map<String,String> retain(Map<String,String> original,Set<String> names) {
    var cookies=cookies(original);cookies.keySet().retainAll(names);
    Map<String,String> next=new LinkedHashMap<>(original);
    if(cookies.isEmpty())next.remove("cookie");
    else next.put("cookie",String.join("; ",cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList()));
    return Collections.unmodifiableMap(next);
  }
  static String text(Outcome outcome){return switch(outcome){case MATCH->"신원 일치";case DIFFERENT->"조건 불일치";case UNKNOWN->"판정 보류";};}

  static Report analyze(MontoyaApi api,Config config,Map<String,String> headers,Set<String> authNames,
      String origin,BooleanSupplier cancelled) {
    Runner runner=new Runner(api,config,headers,authNames,origin,cancelled);
    try {return runner.analyze();}
    catch(Exception error) {
      String note=error instanceof Failure
          ?error.getMessage():"전송 오류 · 검사 중단";
      return new Report(false,false,note==null?"검사 중단":note,List.of(),runner.trials,Set.of(),false);
    }
  }

  /** A fresh identity check before comparison; never uses a past successful result as a lease. */
  static void verify(MontoyaApi api,Config config,Map<String,String> headers,Set<String> names,
      String origin,BooleanSupplier cancelled)throws Exception {
    try {
      Runner runner=new Runner(api,config,headers,names,origin,cancelled);
      if(runner.send("비교 전 신원 확인",headers).outcome()!=Outcome.MATCH)
        throw new Failure("로그인 신원 확인 실패 · 세션 검사에서 다시 확인하세요.");
      Trial anonymous=runner.send("비교 전 비로그인 대조",Map.of());
      if(anonymous.outcome()!=Outcome.DIFFERENT||anonymous.identityMatched())
        throw new Failure("비로그인 응답 구분 실패 · 검증 URL/조건을 다시 확인하세요.");
    } catch(Failure error){throw error;}
    catch(Exception error){throw new Failure("신원 검사 전송 오류 · 비교 중단");}
  }

  private static final class Runner {
    final MontoyaApi api;final Config config;final Map<String,String> original;
    final Set<String> names;final String origin;final BooleanSupplier cancelled;
    final LinkedHashMap<String,String> cookies;
    final List<Trial> trials=new ArrayList<>();
    final Map<Set<String>,Outcome> outcomes=new HashMap<>();
    int sent;
    Runner(MontoyaApi api,Config config,Map<String,String> headers,Set<String> names,String origin,BooleanSupplier cancelled) {
      this.api=api;this.config=config;this.original=Map.copyOf(headers);this.origin=origin;this.cancelled=cancelled;
      Set<String> all=new HashSet<>(names);all.addAll(AuthComparePanel.DEFAULT_AUTH);all.addAll(AuthComparePanel.CSRF);
      all.addAll(headers.keySet());this.names=Set.copyOf(all);cookies=cookies(headers);
    }
    void guard(){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new Failure("검사 중지 · 새 결과 적용 없음");}
    Trial send(String label,Map<String,String> profile)throws Exception {
      guard();
      if(sent>=MAX_REQUESTS)throw new Failure("검사 요청 예산 128개 도달 · 적용 보류");
      HttpRequest request=HttpRequest.httpRequestFromUrl(config.url());
      if(request==null||!"GET".equals(request.method())||!origin.equals(AuthComparePanel.origin(request))
          ||!WorkbenchScope.contains(api,request))throw new Failure("검증 URL은 계정과 같은 Origin/SCOPE 안이어야 합니다.");
      request=Core.sessionRequest(request,profile,names);
      if(sent>0)Thread.sleep(120);
      guard();if(!WorkbenchScope.contains(api,request))throw new Failure("전송 직전 SCOPE 제외 · 검사 중단");
      sent++;
      var rr=api.http().sendRequest(request,RequestOptions.requestOptions().withResponseTimeout(10000).withRedirectionMode(RedirectionMode.NEVER));
      guard();
      if(rr==null||rr.response()==null)throw new Failure("응답 없음 · 검사 중단");
      // A missing actual request cannot prove which credentials Burp sent.
      if(rr.request()==null||!AuthComparePanel.sameTarget(rr.request(),request)||!WorkbenchScope.contains(api,rr.request())
          ||!AuthComparePanel.credentialValues(rr.request(),names).equals(AuthComparePanel.credentialValues(request,names)))
        throw new Failure("실제 전송 대상/인증 변경 · Burp 규칙 확인");
      var response=rr.response();
      if(response.toByteArray().length()>MAX_RESPONSE)throw new Failure("검증 응답 128 KiB 초과 · 판정 보류");
      for(var header:response.headers())if(header.name().equalsIgnoreCase("set-cookie")) {
        String first=header.value().split(";",2)[0];int n=first.indexOf('=');
        if(n>0) {
          String key=first.substring(0,n).strip(),value=first.substring(n+1).strip();
          if(cookies.containsKey(key)&&(!Objects.equals(cookies.get(key),value)
              ||header.value().toLowerCase(Locale.ROOT).matches(".*;\\s*max-age=\\s*(-[0-9]+|0)(;.*)?")))
            throw new Failure("세션 쿠키 갱신 응답 · 새 쿠키를 수집한 뒤 다시 검사하세요.");
        }
      }
      int status=response.statusCode();Outcome outcome=Outcome.UNKNOWN;boolean primaryMatch=false;
      if(status==429||status>=500)throw new Failure("요청 제한/서버 오류 · 검사 중단");
      String note="응답 형식/상태 확인 필요";
      if(status==401||status==403){outcome=Outcome.DIFFERENT;note="인증 거부 응답";}
      else if(status>=200&&status<300) {
        Map<String,Object> json=AuthJudgement.json(JsAnalysisPanel.readBody(response));
        if(json!=null) {
          Object identity=AuthJudgement.value(json,config.identityPath());
          boolean match=(identity instanceof String||identity instanceof Number||identity instanceof Boolean)
              &&config.expectedIdentity().equals(String.valueOf(identity));
          primaryMatch=match;String failed="";
          if(match)for(Claim claim:config.claims()){
            boolean matches=json.entrySet().stream().anyMatch(e->AuthJudgement.match(e.getKey(),claim.path())&&(e.getValue() instanceof String||e.getValue() instanceof Number||e.getValue() instanceof Boolean)&&claim.expected().equals(String.valueOf(e.getValue())));
            if(!matches){failed=claim.label()+" 조건 누락/불일치";break;}
          }
          outcome=match&&failed.isEmpty()?Outcome.MATCH:Outcome.DIFFERENT;
          note=!match?"사용자 식별값 없음/불일치":failed.isEmpty()?"사용자·추가 신원 조건 일치":failed;
        }
      }
      Trial trial=new Trial(label,new ArrayList<>(cookies(profile).keySet()),status,outcome,note,primaryMatch);trials.add(trial);return trial;
    }
    Outcome variant(String label,Set<String> selected)throws Exception {
      Set<String> key=Set.copyOf(selected);if(outcomes.containsKey(key))return outcomes.get(key);
      Map<String,String> profile=new LinkedHashMap<>();
      for(var entry:cookies.entrySet())if(selected.contains(entry.getKey()))
        profile.merge("cookie",entry.getKey()+"="+entry.getValue(),(a,b)->a+"; "+b);
      Outcome outcome=send(label,profile).outcome();outcomes.put(key,outcome);return outcome;
    }
    Report analyze()throws Exception {
      if(original.isEmpty())throw new Failure("검사할 인증 계정을 먼저 지정하세요.");
      Outcome full=send("전체 세션",original).outcome();
      if(full!=Outcome.MATCH)return new Report(false,true,"전체 세션의 사용자 신원을 확인하지 못했습니다.",List.of(),trials,Set.of(),false);
      Trial anonymousTrial=send("비로그인 대조",Map.of());Outcome anonymous=anonymousTrial.outcome();
      if(anonymous!=Outcome.DIFFERENT||anonymousTrial.identityMatched())return new Report(false,false,"비로그인 대조가 구분되지 않음 · 검증 URL/사용자 조건 확인",List.of(),trials,Set.of(),false);
      Set<String> all=new LinkedHashSet<>(cookies.keySet());
      outcomes.put(Set.of(),Outcome.DIFFERENT);
      boolean cookieOnly=!all.isEmpty()&&variant("쿠키만 사용",all)==Outcome.MATCH;
      List<Finding> findings=new ArrayList<>();Set<String> kept=new LinkedHashSet<>(all);boolean complete=true;
      if(cookieOnly) {
        for(String name:all) {
          Set<String> without=new LinkedHashSet<>(all);without.remove(name);
          Outcome excluded=variant("제외: "+name,without),alone=variant("단독: "+name,Set.of(name));
          complete&=excluded!=Outcome.UNKNOWN&&alone!=Outcome.UNKNOWN;
          String result=excluded==Outcome.DIFFERENT?(alone==Outcome.MATCH?"인증에 필요":"조합에 필요")
              :alone==Outcome.MATCH?"단독으로도 유효":excluded==Outcome.MATCH?"이 검사에서 제외 가능":"추가 확인";
          if(result.equals("이 검사에서 제외 가능")&&name.toLowerCase(Locale.ROOT).matches(".*(csrf|xsrf).*"))result="CSRF 이름 후보 · 인증 기여 미관측";
          findings.add(new Finding(name,text(excluded),text(alone),result));
        }
        for(String name:all) {
          Set<String> trial=new LinkedHashSet<>(kept);trial.remove(name);
          Outcome outcome=variant("조합 축소: "+name,trial);
          if(outcome==Outcome.MATCH)kept=trial;else if(outcome==Outcome.UNKNOWN)complete=false;
        }
        if(send("축소 조합 재확인",retain(Map.of("cookie",original.get("cookie")),kept)).outcome()!=Outcome.MATCH)complete=false;
      } else {
        for(String name:all)findings.add(new Finding(name,"미검사","미검사","쿠키만으로 신원 미확인 · 다른 인증과 조합 확인"));
        if(!all.isEmpty()) {
          Outcome other=send("쿠키 없이 다른 인증",retain(original,Set.of())).outcome();
          findings.add(new Finding("다른 인증 헤더",text(other),"—",other==Outcome.MATCH?"쿠키 없이도 신원 확인":"쿠키+다른 인증 조합 필요/추가 확인"));
          complete&=other!=Outcome.UNKNOWN;
        }
      }
      Outcome last=send("전체 세션 재확인",original).outcome();
      boolean valid=last==Outcome.MATCH;complete&=valid;
      return new Report(valid,complete,valid?(complete?(cookieOnly?"신원 확인 완료 · 검증된 쿠키 조합 적용 가능":"신원 확인 완료 · 전체 인증 조합 유지")
          :"일부 판정 보류 · 결과 적용 전 재검사 필요"):"검사 중 세션 상태 변경 · 재검사 필요",findings,trials,kept,cookieOnly);
    }
  }
}
