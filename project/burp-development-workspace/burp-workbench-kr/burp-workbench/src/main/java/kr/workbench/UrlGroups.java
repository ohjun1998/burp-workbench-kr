package kr.workbench;
import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;
/** Groups metadata without deleting or rewriting requests. */
final class UrlGroups {
 record Group(String pattern,List<String> urls,String change,int priority){}
 static List<Group> groups(Collection<String> urls,Set<String> previous){
  Map<String,List<String>> groups=new LinkedHashMap<>();Set<String> priorPatterns=new HashSet<>(),priorPaths=new HashSet<>();
  for(String url:previous){String p=ReconMetadata.pattern(url);priorPatterns.add(p);priorPaths.add(path(p));}
  for(String url:urls)groups.computeIfAbsent("GET "+ReconMetadata.pattern(url),k->new ArrayList<>()).add(url);
  List<Group> out=new ArrayList<>();for(var e:groups.entrySet()){String p=e.getKey().substring(4);String change=priorPatterns.contains(p)?"기존 패턴":priorPaths.contains(path(p))?"파라미터 변경":"신규 패턴";out.add(new Group(e.getKey(),List.copyOf(e.getValue()),change,change.equals("파라미터 변경")?80:change.equals("신규 패턴")?60:20));}
  out.sort(Comparator.comparingInt(Group::priority).reversed().thenComparing(Group::pattern));return List.copyOf(out);
 }
 private static String path(String pattern){int i=pattern.indexOf(" | ");return i<0?pattern:pattern.substring(0,i);}
 static String redact(String url){String safe=ActivityTracker.safeUrl(url);if(safe==null)throw new IllegalArgumentException("HTTP(S) URL/인증값 확인 필요");StringJoiner query=new StringJoiner("&");for(String name:ReconMetadata.parameters(url))query.add(URLEncoder.encode(name,StandardCharsets.UTF_8)+"=");return safe+(query.length()==0?"":"?"+query);}
}
