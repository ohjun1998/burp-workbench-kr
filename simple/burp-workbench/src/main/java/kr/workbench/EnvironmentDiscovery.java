package kr.workbench;
import java.net.URI;
import java.util.*;
import java.util.regex.*;
/** Learns only from captured same-origin data; no requests or JavaScript execution. */
final class EnvironmentDiscovery {
 static String path(URI from,String ref,String origin){try{
  if(ref==null||ref.length()>1024||ref.contains("{")||ref.contains("}")||ref.contains("*")||ref.contains("\\")||ref.matches("(?is).*%(?:2e|2f|5c|00|0a|0d).*"))return null;
  URI u=from.resolve(ref);String p=u.getRawPath();
  if(!EnvironmentCatalog.origin(u).equals(origin)||p==null||!p.startsWith("/")||p.startsWith("//")||p.contains(";")||p.length()>512||!u.normalize().getRawPath().equals(p))return null;
  return p;
 }catch(Exception ignored){return null;}}
 static void detect(EnvironmentCatalog.Site s,String category,String name,String evidence){if(s.detections.size()<40||s.detections.containsKey(name))s.detections.put(name,new EnvironmentCatalog.Detection(category,name,"응답 구조 근거",evidence));}
 static void add(EnvironmentCatalog.Site s,URI from,String ref,String source,String category,int score){String p=path(from,ref,s.origin);if(p==null||s.discovered.size()>=500)return;String url=s.origin+p;var old=s.discovered.get(url);if(old==null||old.score()<score){s.discovered.put(url,new EnvironmentCatalog.Candidate(url,source,EnvironmentCatalog.purpose(p),score,"캡처된 응답에서 발견 · GET 존재/권한 검토 · 쿼리 값 제외"));s.associations.put(url,category);}}
 static void observe(EnvironmentCatalog.Site s,URI from,String headers,String body){
  String responsePath=from.getPath();
  if(responsePath.endsWith("/robots.txt")){
   Matcher m=Pattern.compile("(?im)^\\s*(?:Allow|Disallow|Sitemap):\\s*([^#\\r\\n]+)").matcher(body);int count=0;
   while(m.find()&&count++<100)add(s,from,m.group(1).trim(),"robots.txt 실제 선언","응답 발견",96);
   if(count>0)detect(s,"응답 발견","robots.txt 경로","Allow / Disallow / Sitemap · 존재 미확인");
  }
  if(responsePath.matches("(?i).*sitemap[^/]*\\.xml")){
   Matcher m=Pattern.compile("(?is)<loc>\\s*([^<]+)\\s*</loc>").matcher(body);int count=0;while(m.find()&&count++<100)add(s,from,m.group(1).trim().replace("&amp;","&"),"sitemap 실제 선언","응답 발견",96);
   if(count>0)detect(s,"응답 발견","sitemap 경로","XML loc · 같은 출처만 후보로 사용");
  }
  try{Object value=MinerJson.parse(body);if(!(value instanceof Map<?,?> data))return;
   if(data.containsKey("openapi")||data.containsKey("swagger")){
    detect(s,"API 도구","Swagger / OpenAPI","OpenAPI/Swagger JSON 구조");
    if(data.get("paths") instanceof Map<?,?> paths){
     // JSON keys are declarations, not ordinary URL references. Retain only GET entries.
     for(Object key:paths.keySet())if(key instanceof String p)s.observed.remove(p);
     List<URI> roots=roots(s,from,data);int count=0;
     for(var entry:paths.entrySet()){if(++count>300)break;if(!(entry.getKey() instanceof String p)||!p.startsWith("/")||!(entry.getValue() instanceof Map<?,?> item)||!(item.get("get") instanceof Map<?,?> get))continue;
      List<URI> selected=get.containsKey("servers")?roots(s,from,get):item.containsKey("servers")?roots(s,from,item):roots;
      for(URI root:selected)add(s,root,root.getPath().replaceAll("/+$","")+p,"OpenAPI GET 선언","API 도구",100);
     }
    }
   }
   if(data.get("_links") instanceof Map<?,?> links&&(headers.toLowerCase(Locale.ROOT).contains("spring-boot.actuator")||responsePath.matches(".*/actuator/?"))){
    detect(s,"프레임워크","Spring Boot / Actuator","Actuator 응답 링크 구조");
    if(links.get("self") instanceof Map<?,?> self&&self.get("href") instanceof String ref){String p=path(from,ref,s.origin);if(p!=null&&s.managementRoots.size()<8)s.managementRoots.add(p.replaceAll("/+$",""));}
    int count=0;for(Object v:links.values()){if(++count>100)break;if(v instanceof Map<?,?> link&&link.get("href") instanceof String ref&&!Boolean.TRUE.equals(link.get("templated")))add(s,from,ref,"Actuator 실제 링크","프레임워크",100);}
   }
   // Swagger UI configuration can reference renamed/relative schema documents.
   if(responsePath.contains("swagger")||s.detections.containsKey("Swagger / OpenAPI")){
    if(data.get("url") instanceof String ref)add(s,from,ref,"Swagger 명세 참조","API 도구",98);
    if(data.get("urls") instanceof List<?> urls)for(Object v:urls.stream().limit(20).toList())if(v instanceof Map<?,?> item&&item.get("url") instanceof String ref)add(s,from,ref,"Swagger 명세 참조","API 도구",98);
   }
  }catch(IllegalArgumentException ignored){}
 }
 static List<URI> roots(EnvironmentCatalog.Site s,URI from,Map<?,?> data){
  List<URI> result=new ArrayList<>();
  if(data.get("servers") instanceof List<?> servers){for(Object v:servers.stream().limit(8).toList())if(v instanceof Map<?,?> item&&item.get("url") instanceof String ref){String p=path(from,ref,s.origin);if(p!=null){result.add(URI.create(s.origin+p));if(s.prefixes.size()<8&&!p.equals("/"))try{s.prefixes.add(EnvironmentCatalog.prefix(p));}catch(Exception ignored){}}}return result;}
  if(data.get("host") instanceof String host&&!host.equalsIgnoreCase(from.getRawAuthority()))return result;
  String base=data.get("basePath") instanceof String b?b:"/";String p=path(URI.create(s.origin+"/"),base,s.origin);if(p!=null)result.add(URI.create(s.origin+p));return result;
 }
 record ImportResult(int accepted,int skipped){}
 static ImportResult importWords(EnvironmentCatalog.Site s,String text,String name){
  if(text.length()>1024*1024)throw new IllegalArgumentException("목록은 1MiB 이하 UTF-8 파일을 사용하세요.");
  int accepted=0,skipped=0;String safeName=name.replaceAll("[^\\p{L}\\p{N}._-]","_");safeName=safeName.substring(0,Math.min(80,safeName.length()));
  for(String raw:text.split("\\R")){String word=raw.strip();if(word.isEmpty()||word.startsWith("#"))continue;if(accepted>=1000){skipped++;continue;}
   // Wordlists are relative path facts, not URLs, templates or request payloads.
   String p=word.startsWith("/")?word:"/"+word;try{EnvironmentCatalog.prefix(p);}catch(IllegalArgumentException e){skipped++;continue;}
   if(word.contains(":")||word.contains("?")||word.contains("#")||word.chars().anyMatch(Character::isWhitespace)||path(URI.create(s.origin+"/"),p,s.origin)==null){skipped++;continue;}
   String key=s.origin+p;if(s.discovered.containsKey(key)){skipped++;continue;}if(s.discovered.size()>=500){skipped++;continue;}
   s.discovered.put(key,new EnvironmentCatalog.Candidate(key,"사용자 목록 · "+safeName,EnvironmentCatalog.purpose(p),40,"사용자 제공 · 환경/존재 근거 없음"));s.associations.put(key,"사용자 목록");accepted++;
  }
  if(accepted>0)detect(s,"사용자 목록","가져온 경로 목록","같은 출처 경로 · 최대 500개 유지");return new ImportResult(accepted,skipped);
 }
}
