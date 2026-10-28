package kr.workbench;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Explicit, bounded browser command lease. Browser cookies and bodies never cross this bridge. */
final class ScopeInspectionBrowser implements AutoCloseable {
  record Snapshot(String runId, String state, String message, int pages, int actions, int requests,
                  int excluded, long startedAt, long finishedAt) {}
  private final Consumer<Map<String,Object>> listener;
  private final Predicate<String> liveScope;
  private String runId="", startUrl="", origin="", state="IDLE", message="브라우저 연결 대기";
  private List<String> includes=List.of(), excludes=List.of();
  private int maxPages, maxActions, pages, actions, requests, excluded, events;
  private long expiresAt, startedAt, finishedAt;
  private boolean closed;
  private final Set<String> seen = new LinkedHashSet<>();

  ScopeInspectionBrowser(Consumer<Map<String,Object>> listener) { this(listener, url -> true); }
  ScopeInspectionBrowser(Consumer<Map<String,Object>> listener, Predicate<String> liveScope) {
    this.listener=Objects.requireNonNull(listener); this.liveScope=Objects.requireNonNull(liveScope);
  }

  synchronized void begin(String id,String url,List<String> include,List<String> exclude,
                          int pageLimit,int actionLimit,long expiry) {
    if(closed)throw new IllegalStateException("브라우저 점검이 종료됐습니다.");
    if(id==null||!id.matches("[A-Za-z0-9_.:-]{1,100}")||pageLimit<1||pageLimit>100
        ||actionLimit<1||actionLimit>300||expiry<=System.currentTimeMillis()
        ||expiry-System.currentTimeMillis()>15*60_000L)throw new IllegalArgumentException("브라우저 점검 한도를 확인하세요.");
    URI start=valid(url); String site=origin(start);
    List<String> allowed=rules(site,include,false), denied=rules(site,exclude,true);
    if(allowed.stream().mapToInt(String::length).sum()+denied.stream().mapToInt(String::length).sum()>40000)throw new IllegalArgumentException("브라우저 Scope 규칙 크기 제한");
    if(allowed.isEmpty()||!accepts(url,site,allowed,denied)||!liveScope.test(url))
      throw new IllegalArgumentException("브라우저 시작 URL이 허용 Scope에 없습니다.");
    runId=id;startUrl=url;origin=site;includes=allowed;excludes=denied;
    maxPages=pageLimit;maxActions=actionLimit;expiresAt=expiry;pages=actions=requests=excluded=events=0;
    startedAt=System.currentTimeMillis();finishedAt=0;seen.clear();state="WAITING";
    message="도우미의 SPA 통합 탐색 허용과 사이트 활성화를 확인하세요.";
  }

  synchronized Map<String,Object> command(String site) {
    if(closed)return Map.of("state","idle");
    if(!origin.equals(site))return Map.of("state","idle");
    if(active() && (System.currentTimeMillis()>=expiresAt || !liveScope.test(startUrl))) {
      state="STOPPED";finishedAt=System.currentTimeMillis();message="브라우저 시간 한도 또는 Scope 변경으로 중지";
    }
    if(state.equals("STOPPED")||state.equals("FAILED"))return Map.of("state","cancel","runId",runId);
    if(!active())return Map.of("state","idle","runId",runId);
    Map<String,Object> task=new LinkedHashMap<>();
    task.put("state",state.equals("WAITING")?"run":"running");task.put("runId",runId);
    task.put("startUrl",startUrl);task.put("origin",origin);task.put("includes",includes);task.put("excludes",excludes);
    task.put("maxPages",maxPages);task.put("maxActions",maxActions);task.put("expiresAt",expiresAt);
    task.put("actor","browser-session");task.put("httpProfileShared",false);
    if(state.equals("WAITING")){state="DISPATCHED";message="브라우저 명령 전달 · 전용 탭 시작 대기";}
    return Map.copyOf(task);
  }

  void event(Map<String,Object> input) {
    Map<String,Object> clean;
    synchronized(this) {
      if(active()&&System.currentTimeMillis()>=expiresAt){state="STOPPED";finishedAt=System.currentTimeMillis();message="브라우저 시간 한도";return;}
      if(closed||!Objects.equals(runId,input.get("runId"))||!"inspection".equals(input.get("type"))
          ||!active()||events>=5000)return;
      String kind=text(input.get("kind"),32);
      if(!Set.of("started","route","menu","api","excluded","progress","done","error").contains(kind))return;
      String url=text(input.get("url"),8192), page=text(input.get("page"),8192);
      if(!url.isEmpty()&&!kind.equals("excluded")&&(!accepts(url,origin,includes,excludes)||!liveScope.test(url))) {
        excluded++; return;
      }
      if(!page.isEmpty()&&!kind.equals("excluded")&&(!accepts(page,origin,includes,excludes)||!liveScope.test(page)))return;
      String method=text(input.get("method"),16).toUpperCase(Locale.ROOT);
      if(!method.isEmpty()&&!method.matches("[A-Z]{1,16}"))return;
      String routeId=text(input.get("routeId"),128);
      if(!routeId.isEmpty()&&!routeId.matches("[a-fA-F0-9]{16,128}"))return;
      String pageRouteId=text(input.get("pageRouteId"),128);
      if(!pageRouteId.isEmpty()&&!pageRouteId.matches("[a-fA-F0-9]{16,128}"))return;
      String label=masked(text(input.get("label"),120));
      String key=kind+"|"+safe(url)+"|"+routeId+"|"+pageRouteId+"|"+method+"|"+label;
      if(Set.of("route","menu","api").contains(kind)&&!seen.add(key))return;
      events++; pages=max(pages,input.get("pages"),maxPages);actions=max(actions,input.get("actions"),maxActions);
      requests=max(requests,input.get("requests"),100000);excluded=max(excluded,input.get("excluded"),100000);
      clean=new LinkedHashMap<>();clean.put("type","inspection");clean.put("runId",runId);clean.put("kind",kind);
      clean.put("state",text(input.get("state"),40));clean.put("message",masked(text(input.get("message"),300)));
      clean.put("url",safe(url));clean.put("page",safe(page));clean.put("method",method);clean.put("label",label);
      clean.put("routeId",routeId);clean.put("pageRouteId",pageRouteId);clean.put("association","tab-current-route-observation");clean.put("tab",text(input.get("tab"),32));clean.put("actor","browser-session");
      clean.put("requiresQuery",Boolean.TRUE.equals(input.get("requiresQuery")));
      Object query=input.get("queryNames");
      clean.put("queryNames",query instanceof List<?> list?list.stream().limit(32).map(x->masked(text(x,80))).toList():List.of());
      clean.put("pages",pages);clean.put("actions",actions);clean.put("requests",requests);clean.put("excluded",excluded);
      clean.put("httpProfileShared",false);
      if(kind.equals("started")){state="RUNNING";message="브라우저 로그인 세션으로 전용 탭 탐색 중";}
      if(kind.equals("done")){
        state="stopped".equals(input.get("state"))?"STOPPED":"COMPLETED";finishedAt=System.currentTimeMillis();
        message="stopped".equals(input.get("state"))?"브라우저 메뉴 탐색 중지":
            "partial".equals(input.get("state"))?"일부 탐색 · "+clean.get("message"):
            excluded>0?"일부 탐색 · 범위 밖 리소스 제외":"발견한 브라우저 메뉴 큐 처리 완료";
      }
      if(kind.equals("error")){state="FAILED";finishedAt=System.currentTimeMillis();message=Objects.toString(clean.get("message"),"브라우저 탐색 실패");}
    }
    listener.accept(Map.copyOf(clean));
  }

  synchronized boolean active() { return !closed&&Set.of("WAITING","DISPATCHED","RUNNING").contains(state); }
  synchronized Snapshot snapshot() { return new Snapshot(runId,state,message,pages,actions,requests,excluded,startedAt,finishedAt); }
  synchronized void stop(){if(active()){state="STOPPED";finishedAt=System.currentTimeMillis();message="브라우저 탐색 중지 · 전용 탭 정리 대기";}}
  @Override public synchronized void close(){stop();closed=true;state="CLOSED";seen.clear();}

  private static int max(int old,Object value,int limit){return value instanceof Number n?Math.max(old,Math.min(limit,Math.max(0,n.intValue()))):old;}
  private static String text(Object value,int max){return Objects.toString(value,"").replaceAll("[\\r\\n\\t]"," ").substring(0,Math.min(max,Objects.toString(value,"").length()));}
  private static String masked(String value){
    String clean=value.replaceAll("(?i)(?:AKIA|ASIA)[A-Z0-9]{16}|AIza[0-9A-Za-z_-]{35}|gh[pousr]_[A-Za-z0-9]{36,255}|sk_(?:live|test)_[A-Za-z0-9]{16,200}|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}","[REDACTED]");
    return clean.replaceAll("(?i)((?:api[_-]?key|token|secret|password|authorization|cookie)\\s*[:=]\\s*)[^ ,;]+","$1[REDACTED]");
  }
  private static String safe(String url){if(url.isEmpty())return "";try{URI u=valid(url);return origin(u)+path(u);}catch(RuntimeException invalid){return "";}}
  static URI valid(String url) {
    if(url==null||url.length()>8192||url.matches("(?s).*[\\\\\\r\\n\\t].*"))throw new IllegalArgumentException("HTTP URL 형식 오류");
    String raw=url.split("[?#]",2)[0];
    if(raw.matches("(?i).*(?:%2e|%2f|%5c|%25|%00|/\\.\\.?/|/\\.\\.?$|;).*")||raw.contains("/../")||raw.contains("/./"))throw new IllegalArgumentException("모호한 경로 제외");
    URI u=URI.create(url);if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null)throw new IllegalArgumentException("HTTP(S) Origin 필요");
    return u;
  }
  private static String path(URI u){return u.getRawPath()==null||u.getRawPath().isEmpty()?"/":u.getRawPath();}
  static String origin(URI u){String host=u.getHost().toLowerCase(Locale.ROOT);if(host.contains(":")&&!host.startsWith("["))host="["+host+"]";return u.getScheme()+"://"+host+(u.getPort()<0||u.getPort()==80&&u.getScheme().equals("http")||u.getPort()==443&&u.getScheme().equals("https")?"":":"+u.getPort());}
  private static List<String> rules(String site,List<String> values,boolean exclusion){
    if(values.size()>100)throw new IllegalArgumentException("브라우저 Scope 규칙은 최대 100개");
    LinkedHashSet<String> result=new LinkedHashSet<>();
    for(String value:values){URI u=valid(value.startsWith("/")?site+value:value);if(!origin(u).equals(site)||u.getRawQuery()!=null||u.getRawFragment()!=null)throw new IllegalArgumentException("브라우저 Scope는 같은 Origin의 경로 규칙이어야 합니다.");String path=path(u);while(path.length()>1&&path.endsWith("/"))path=path.substring(0,path.length()-1);result.add(site+path);}
    return List.copyOf(result);
  }
  static boolean accepts(String value,String site,List<String> allow,List<String> deny){try{URI u=valid(value);if(!origin(u).equals(site))return false;String url=site+path(u);for(String rule:deny)if(url.startsWith(rule))return false;return allow.stream().anyMatch(rule->url.equals(rule)||rule.endsWith("/")&&url.startsWith(rule)||url.startsWith(rule+"/"));}catch(RuntimeException invalid){return false;}}
}
