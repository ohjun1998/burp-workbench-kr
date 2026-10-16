package kr.workbench;

import burp.api.montoya.MontoyaApi;import burp.api.montoya.http.message.requests.HttpRequest;
import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;

/** Extension-local scope; configuring it replaces the Burp Scope fallback. */
public final class WorkbenchScope {
 private static final Map<MontoyaApi,WorkbenchScope> scopes=Collections.synchronizedMap(new WeakHashMap<>());
 private static final String STORE="wb.scope.v1";private static final int LIMIT=200;
 public record Rule(String url,boolean originOnly,String burpKey){public Rule(String url,boolean originOnly){this(url,originOnly,null);}}
 private final MontoyaApi api;private final LinkedHashSet<Rule> rules=new LinkedHashSet<>();private boolean configured;
 private final Set<Rule> disabled=new HashSet<>();private final Set<String> suppressed=new LinkedHashSet<>();private final Map<String,BurpScopeConfig.Entry> burpEntries=new LinkedHashMap<>();
 private List<BurpScopeConfig.Entry> burpExclusions=List.of();
 private WorkbenchScope(MontoyaApi api){this.api=api;try{String data=api.persistence().extensionData().getString(STORE);if(data!=null)load(data);}catch(Exception ignored){}}
 static WorkbenchScope install(MontoyaApi api){WorkbenchScope scope=new WorkbenchScope(api);scopes.put(api,scope);return scope;}
 static void release(MontoyaApi api){scopes.remove(api);}
 static List<Rule> registeredRules(MontoyaApi api){WorkbenchScope scope=scopes.get(api);return scope==null?List.of():scope.rules().stream().filter(r->scope.enabled(r)&&canonical(r.url())!=null).toList();}
 public static boolean contains(MontoyaApi api,HttpRequest request){if(request==null)return false;WorkbenchScope scope=scopes.get(api);try{return scope!=null&&scope.configured()?scope.matches(request.url()):request.isInScope();}catch(Exception ex){return false;}}
 public static boolean contains(MontoyaApi api,String url){WorkbenchScope scope=scopes.get(api);try{return scope!=null&&scope.configured()?scope.matches(url):api.scope().isInScope(url);}catch(Exception ex){return false;}}
 public static boolean legacy(MontoyaApi api,String url,boolean burpScope){WorkbenchScope scope=scopes.get(api);return scope!=null&&scope.configured()?scope.matches(url):burpScope;}
 public static String reason(MontoyaApi api,HttpRequest q){if(q==null)return "요청 없음";WorkbenchScope scope=scopes.get(api);if(scope!=null&&scope.configured())return scope.explanation(q.url());try{return q.isInScope()?"Burp Scope 포함 · Workbench 규칙 미지정":"Burp Scope 제외 · Workbench 규칙 미지정";}catch(Exception ex){return "Burp Scope 확인 실패";}}
 public static String reason(MontoyaApi api,String url){WorkbenchScope scope=scopes.get(api);if(scope!=null&&scope.configured())return scope.explanation(url);try{return api.scope().isInScope(url)?"Burp Scope 포함 · Workbench 규칙 미지정":"Burp Scope 제외 · Workbench 규칙 미지정";}catch(Exception ex){return "Burp Scope 확인 실패";}}
 synchronized String explanation(String raw){String url=canonical(raw);if(url==null)return "SCOPE 제외 · URL/경로 형식 확인";if(rules.isEmpty())return "SCOPE 제외 · 등록된 범위 없음";Rule best=rules.stream().filter(r->!disabled.contains(r)&&ruleMatches(r,raw,url)).max(Comparator.comparingInt(r->r.burpKey()!=null||r.originOnly()?-1:URI.create(r.url).getRawPath().length())).orElse(null);return best==null?"SCOPE 제외 · 일치하는 체크된 규칙 없음 또는 Burp 제외 범위":"SCOPE 포함 · "+(best.burpKey()!=null?"Burp Scope: ":best.originOnly?"도메인 전체: ":"URL 경로: ")+best.url;}
 private boolean ruleMatches(Rule rule,String raw,String url){if(rule.burpKey()==null)return covers(rule,url)&&burpExclusions.stream().noneMatch(e->e.enabled()&&e.matches(raw));var entry=burpEntries.get(rule.burpKey());if(entry!=null&&entry.matches(raw)){try{return api.scope().isInScope(raw);}catch(Exception ignored){}}return false;}
 private static boolean covers(Rule rule,String url){if(rule.burpKey()!=null||canonical(url)==null)return false;if(!origin(rule.url).equals(origin(url)))return false;String path=URI.create(url).getRawPath(),prefix=URI.create(rule.url).getRawPath();return rule.originOnly||path.equals(prefix)||path.startsWith(prefix.endsWith("/")?prefix:prefix+"/");}
 synchronized boolean enabled(Rule rule){return !disabled.contains(rule);}
 synchronized void setEnabled(Rule rule,boolean enabled){if(!rules.contains(rule))return;configured=true;if(enabled)disabled.remove(rule);else disabled.add(rule);persist();}
 synchronized void clear(){configured=true;for(Rule rule:rules)if(rule.burpKey()!=null)suppressed.add(rule.burpKey());rules.clear();disabled.clear();persist();}
 synchronized boolean syncBurp(String json){
  if(json==null||json.isBlank())return false;
  List<BurpScopeConfig.Entry> entries=BurpScopeConfig.parse(json);List<BurpScopeConfig.Entry> exclusions=BurpScopeConfig.exclusions(json);Set<String> keys=new HashSet<>();for(var e:entries)keys.add(e.key());
  burpExclusions=exclusions;
  boolean changed=rules.removeIf(r->r.burpKey()!=null&&!keys.contains(r.burpKey()));disabled.retainAll(rules);changed|=suppressed.retainAll(keys);burpEntries.clear();
  for(var e:entries){burpEntries.put(e.key(),e);if(suppressed.contains(e.key()))continue;Rule rule=new Rule(e.label(),false,e.key());if(rules.add(rule)){if(!e.enabled())disabled.add(rule);changed=true;}}
  if(!entries.isEmpty()||changed)configured=true;if(changed)persist();return changed;
 }
 /** Some Burp editions omit target.scope from configuration exports. Recover
  * observable URL prefixes using only the public native Scope predicate. Every
  * capture still checks that predicate, including native exclusion rules. */
 synchronized boolean syncObserved(Collection<String> candidates){
  boolean changed=suppressed.removeIf(k->k.startsWith("observed:")&&!nativeContains(k.substring(9)));
  for(Rule rule:new ArrayList<>(rules))if(rule.burpKey()!=null&&rule.burpKey().startsWith("observed:")){
   if(nativeContains(rule.url()))burpEntries.put(rule.burpKey(),observedEntry(rule.url()));
   else {rules.remove(rule);disabled.remove(rule);burpEntries.remove(rule.burpKey());changed=true;}
  }
  for(String candidate:candidates){
   String url=canonical(candidate);if(url==null||!nativeContains(url))continue;
   // A disabled or deleted parent must not be bypassed by a newly seen child.
   if(rules.stream().anyMatch(r->r.burpKey()!=null&&r.burpKey().startsWith("observed:")&&url.startsWith(r.url())))continue;
   if(suppressed.stream().filter(k->k.startsWith("observed:")).anyMatch(k->url.startsWith(k.substring(9))))continue;
   String prefix=observedPrefix(url),key="observed:"+prefix;
   Rule rule=new Rule(prefix,false,key);
   burpEntries.put(key,observedEntry(prefix));
   if(!suppressed.contains(key)&&rules.add(rule))changed=true;
  }
  if(changed){configured=true;persist();}return changed;
 }
 private boolean nativeContains(String url){try{return api.scope().isInScope(url);}catch(RuntimeException e){return false;}}
 private String observedPrefix(String url){
  URI u=URI.create(url);String base=origin(url),path=Objects.toString(u.getRawPath(),"/");
  if(nativeContains(base+"/"))return base+"/";
  int start=1;
  while(start<path.length()){
   int end=path.indexOf('/',start);if(end<0)end=path.length();
   String prefix=base+path.substring(0,end);
   if(nativeContains(prefix))return prefix;
   if(end<path.length()&&nativeContains(prefix+"/"))return prefix+"/";
   start=end+1;
  }
  return url;
 }
 private static BurpScopeConfig.Entry observedEntry(String prefix){return new BurpScopeConfig.Entry("observed:"+prefix,prefix,true,prefix,null,null,null,null);}
 synchronized String overlap(Rule rule){if(rule.burpKey()!=null)return "Burp Target Scope · 제외 규칙도 적용";for(Rule other:rules)if(!other.equals(rule)&&covers(other,rule.url)&&(!rule.originOnly||other.originOnly||URI.create(other.url).getRawPath().equals("/")))return "다른 규칙에 포함: "+other.url;long count=rules.stream().filter(other->!other.equals(rule)&&covers(rule,other.url)&&(!other.originOnly||rule.originOnly||URI.create(rule.url).getRawPath().equals("/"))).count();return count>0?"하위 규칙 "+count+"개 포함":"독립 범위";}
 synchronized void importRules(String data){Object parsed=MinerJson.parse(data);if(!(parsed instanceof Map<?,?> doc)||!(doc.get("version") instanceof Number v)||(v.intValue()!=1&&v.intValue()!=2)||!(doc.get("rules") instanceof List<?> list))throw new IllegalArgumentException("SCOPE 버전/개수 형식 오류");for(Object item:list){if(!(item instanceof Map<?,?> row)||!(row.get("url") instanceof String url)||(!(row.get("burpKey") instanceof String)&&canonical(url)==null)||!(row.get("originOnly") instanceof Boolean)||(row.containsKey("enabled")&&!(row.get("enabled") instanceof Boolean)))throw new IllegalArgumentException("유효하지 않은 SCOPE 규칙");}load(data);configured=true;persist();}
 synchronized boolean configured(){return configured;}
 synchronized List<Rule> rules(){return List.copyOf(rules);}
 synchronized void add(String raw,boolean originOnly){String url=canonical(raw);if(url==null)throw new IllegalArgumentException("올바른 HTTP(S) URL을 선택하세요.");if(originOnly)url=origin(url)+"/";Rule rule=new Rule(url,originOnly);if(!rules.contains(rule)&&rules.size()>=LIMIT)throw new IllegalArgumentException("SCOPE는 최대 200개입니다.");configured=true;rules.add(rule);disabled.remove(rule);persist();}
 /** Replace a local rule atomically, retaining its position and checkbox state. */
 synchronized Rule update(Rule original,String raw){
  if(!rules.contains(original))throw new IllegalArgumentException("수정할 URL을 다시 선택하세요.");
  if(original.burpKey()!=null)throw new IllegalArgumentException("Burp Scope는 Burp에서 수정하세요.");
  String url=canonical(raw);
  if(url==null)throw new IllegalArgumentException("올바른 HTTP(S) URL을 입력하세요.");
  if(original.originOnly())url=origin(url)+"/";
  Rule replacement=new Rule(url,original.originOnly());
  if(!replacement.equals(original)&&rules.contains(replacement))throw new IllegalArgumentException("이미 등록된 URL입니다.");
  boolean wasDisabled=disabled.contains(original);
  List<Rule> updated=rules.stream().map(r->r.equals(original)?replacement:r).toList();
  rules.clear();rules.addAll(updated);disabled.remove(original);
  if(wasDisabled)disabled.add(replacement);
  configured=true;persist();return replacement;
 }
 synchronized void remove(Rule rule){configured=true;rules.remove(rule);disabled.remove(rule);if(rule.burpKey()!=null)suppressed.add(rule.burpKey());persist();}
 /** Traffic and explicit tools share checked local/native rules. Native rules
  * still use Burp's live predicate; local rules honor exported exclusions. */
 synchronized boolean matchesTraffic(String raw){return matches(raw);}
 synchronized boolean matches(String raw){String url=canonical(raw);if(url==null)return false;for(Rule rule:rules)if(!disabled.contains(rule)&&ruleMatches(rule,raw,url))return true;return false;}
 static String canonical(String raw){String url=ActivityTracker.safeUrl(raw);if(url==null)return null;try {URI u=URI.create(url).normalize();for(String segment:u.getRawPath().split("/",-1)){String decoded=URLDecoder.decode(segment.replace("+","%2B"),StandardCharsets.UTF_8);if(decoded.equals(".")||decoded.equals("..")||decoded.contains("/")||decoded.contains("\\")||decoded.chars().anyMatch(c->c<32))return null;}return u.toString();}catch(Exception ex){return null;}}
 private static String origin(String url){URI u=URI.create(url);return u.getScheme()+"://"+u.getRawAuthority();}
 synchronized String save(){return MetadataExport.json(Map.of("version",2,"configured",configured,"suppressed",new ArrayList<>(suppressed),"rules",rules.stream().map(r->{Map<String,Object> row=new LinkedHashMap<>();row.put("url",r.url);row.put("originOnly",r.originOnly);row.put("enabled",enabled(r));if(r.burpKey()!=null)row.put("burpKey",r.burpKey());return row;}).toList()));}
 synchronized void load(String data){Object obj=MinerJson.parse(data);if(!(obj instanceof Map<?,?> root)||!(root.get("rules") instanceof List<?> list)||!(root.get("version") instanceof Number v)||(v.intValue()!=1&&v.intValue()!=2))throw new IllegalArgumentException("SCOPE 저장 형식 오류");LinkedHashSet<Rule> imported=new LinkedHashSet<>();Set<Rule> unchecked=new HashSet<>();for(Object row:list){if(row instanceof Map<?,?> map){String key=map.get("burpKey") instanceof String k?k:null;String url=key==null?canonical(Objects.toString(map.get("url"),"")):Objects.toString(map.get("url"),"");if(url!=null){Rule rule=new Rule(key==null&&Boolean.TRUE.equals(map.get("originOnly"))?origin(url)+"/":url,Boolean.TRUE.equals(map.get("originOnly")),key);imported.add(rule);if(Boolean.FALSE.equals(map.get("enabled")))unchecked.add(rule);}}}rules.clear();rules.addAll(imported);disabled.clear();disabled.addAll(unchecked);suppressed.clear();if(root.get("suppressed") instanceof List<?> deleted)for(Object key:deleted)if(key instanceof String k)suppressed.add(k);configured=Boolean.TRUE.equals(root.get("configured"));}
 private void persist(){try{api.persistence().extensionData().setString(STORE,save());}catch(Exception ignored){}}
}
