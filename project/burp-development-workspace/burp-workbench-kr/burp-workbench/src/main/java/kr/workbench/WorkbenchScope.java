package kr.workbench;

import burp.api.montoya.MontoyaApi;import burp.api.montoya.http.message.requests.HttpRequest;
import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;

/** Extension-local scope; configuring it replaces the Burp Scope fallback. */
public final class WorkbenchScope {
 private static final Map<MontoyaApi,WorkbenchScope> scopes=Collections.synchronizedMap(new WeakHashMap<>());
 private static final String STORE="wb.scope.v1";private static final int LIMIT=200;
 public record Rule(String url,boolean originOnly){}
 private final MontoyaApi api;private final LinkedHashSet<Rule> rules=new LinkedHashSet<>();private boolean configured;
 private WorkbenchScope(MontoyaApi api){this.api=api;try{String data=api.persistence().extensionData().getString(STORE);if(data!=null)load(data);}catch(Exception ignored){}}
 static WorkbenchScope install(MontoyaApi api){WorkbenchScope scope=new WorkbenchScope(api);scopes.put(api,scope);return scope;}
 static void release(MontoyaApi api){scopes.remove(api);}
 static List<Rule> registeredRules(MontoyaApi api){WorkbenchScope scope=scopes.get(api);return scope==null?List.of():scope.rules();}
 public static boolean contains(MontoyaApi api,HttpRequest request){if(request==null)return false;WorkbenchScope scope=scopes.get(api);try{return scope!=null&&scope.configured()?scope.matches(request.url()):request.isInScope();}catch(Exception ex){return false;}}
 public static boolean contains(MontoyaApi api,String url){WorkbenchScope scope=scopes.get(api);try{return scope!=null&&scope.configured()?scope.matches(url):api.scope().isInScope(url);}catch(Exception ex){return false;}}
 public static boolean legacy(MontoyaApi api,String url,boolean burpScope){WorkbenchScope scope=scopes.get(api);return scope!=null&&scope.configured()?scope.matches(url):burpScope;}
 public static String reason(MontoyaApi api,HttpRequest q){if(q==null)return "요청 없음";WorkbenchScope scope=scopes.get(api);if(scope!=null&&scope.configured())return scope.explanation(q.url());try{return q.isInScope()?"Burp Scope 포함 · Workbench 규칙 미지정":"Burp Scope 제외 · Workbench 규칙 미지정";}catch(Exception ex){return "Burp Scope 확인 실패";}}
 public static String reason(MontoyaApi api,String url){WorkbenchScope scope=scopes.get(api);if(scope!=null&&scope.configured())return scope.explanation(url);try{return api.scope().isInScope(url)?"Burp Scope 포함 · Workbench 규칙 미지정":"Burp Scope 제외 · Workbench 규칙 미지정";}catch(Exception ex){return "Burp Scope 확인 실패";}}
 synchronized String explanation(String raw){String url=canonical(raw);if(url==null)return "SCOPE 제외 · URL/경로 형식 확인";if(rules.isEmpty())return "SCOPE 제외 · 등록된 범위 없음";Rule best=rules.stream().filter(r->covers(r,url)).max(Comparator.comparingInt(r->r.originOnly?-1:URI.create(r.url).getRawPath().length())).orElse(null);return best==null?"SCOPE 제외 · 일치하는 프로토콜/호스트/포트/경로 규칙 없음":"SCOPE 포함 · "+(best.originOnly?"도메인 전체: ":"URL 경로: ")+best.url;}
 private static boolean covers(Rule rule,String url){if(!origin(rule.url).equals(origin(url)))return false;String path=URI.create(url).getRawPath(),prefix=URI.create(rule.url).getRawPath();return rule.originOnly||path.equals(prefix)||path.startsWith(prefix.endsWith("/")?prefix:prefix+"/");}
 synchronized String overlap(Rule rule){for(Rule other:rules)if(!other.equals(rule)&&covers(other,rule.url)&&(!rule.originOnly||other.originOnly||URI.create(other.url).getRawPath().equals("/")))return "다른 규칙에 포함: "+other.url;long count=rules.stream().filter(other->!other.equals(rule)&&covers(rule,other.url)&&(!other.originOnly||rule.originOnly||URI.create(rule.url).getRawPath().equals("/"))).count();return count>0?"하위 규칙 "+count+"개 포함":"독립 범위";}
 synchronized void importRules(String data){Object parsed=MinerJson.parse(data);if(!(parsed instanceof Map<?,?> doc)||!(doc.get("version") instanceof Number v)||v.intValue()!=1||!(doc.get("rules") instanceof List<?> list)||list.size()>LIMIT)throw new IllegalArgumentException("SCOPE 버전/개수 형식 오류");for(Object item:list){if(!(item instanceof Map<?,?> row)||!(row.get("url") instanceof String url)||canonical(url)==null||!(row.get("originOnly") instanceof Boolean))throw new IllegalArgumentException("유효하지 않은 SCOPE 규칙");}load(data);configured=true;persist();}
 synchronized boolean configured(){return configured;}
 synchronized List<Rule> rules(){return List.copyOf(rules);}
 synchronized void add(String raw,boolean originOnly){String url=canonical(raw);if(url==null)throw new IllegalArgumentException("올바른 HTTP(S) URL을 선택하세요.");if(originOnly)url=origin(url)+"/";Rule rule=new Rule(url,originOnly);if(!rules.contains(rule)&&rules.size()>=LIMIT)throw new IllegalArgumentException("SCOPE는 최대 200개입니다.");configured=true;rules.add(rule);persist();}
 synchronized void remove(Rule rule){configured=true;rules.remove(rule);persist();}
 synchronized boolean matches(String raw){String url=canonical(raw);if(url==null)return false;String origin=origin(url),path=URI.create(url).getRawPath();for(Rule rule:rules){if(!origin.equals(origin(rule.url)))continue;String prefix=URI.create(rule.url).getRawPath();if(rule.originOnly||path.equals(prefix)||path.startsWith(prefix.endsWith("/")?prefix:prefix+"/"))return true;}return false;}
 static String canonical(String raw){String url=ActivityTracker.safeUrl(raw);if(url==null)return null;try {URI u=URI.create(url).normalize();for(String segment:u.getRawPath().split("/",-1)){String decoded=URLDecoder.decode(segment.replace("+","%2B"),StandardCharsets.UTF_8);if(decoded.equals(".")||decoded.equals("..")||decoded.contains("/")||decoded.contains("\\")||decoded.chars().anyMatch(c->c<32))return null;}return u.toString();}catch(Exception ex){return null;}}
 private static String origin(String url){URI u=URI.create(url);return u.getScheme()+"://"+u.getRawAuthority();}
 synchronized String save(){return MetadataExport.json(Map.of("version",1,"configured",configured,"rules",rules.stream().map(r->Map.of("url",r.url,"originOnly",r.originOnly)).toList()));}
 synchronized void load(String data){Object obj=MinerJson.parse(data);if(!(obj instanceof Map<?,?> root)||!(root.get("rules") instanceof List<?> list)||!(root.get("version") instanceof Number v)||v.intValue()!=1)throw new IllegalArgumentException("SCOPE 저장 형식 오류");LinkedHashSet<Rule> imported=new LinkedHashSet<>();for(Object row:list){if(imported.size()>=LIMIT)break;if(row instanceof Map<?,?> map){String url=canonical(Objects.toString(map.get("url"),""));if(url!=null)imported.add(new Rule(Boolean.TRUE.equals(map.get("originOnly"))?origin(url)+"/":url,Boolean.TRUE.equals(map.get("originOnly"))));}}rules.clear();rules.addAll(imported);configured=Boolean.TRUE.equals(root.get("configured"));}
 private void persist(){try{api.persistence().extensionData().setString(STORE,save());}catch(Exception ignored){}}
}
