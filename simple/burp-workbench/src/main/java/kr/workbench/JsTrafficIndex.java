package kr.workbench;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.util.*;
import java.util.regex.*;

/** Bounded observations of collected traffic only; stores field names, never values or packets. */
final class JsTrafficIndex {
  record Entry(Set<String> methods,Set<String> fields,Set<Integer> statuses,long count){}
  private final LinkedHashMap<String,Entry> rows=new LinkedHashMap<>();
  volatile long revision,evicted;
  synchronized void observe(HttpRequest request,HttpResponse response){
    String url=key(request.url());if(url==null)return;Entry old=rows.get(url);
    Set<String> methods=new TreeSet<>(old==null?Set.of():old.methods());methods.add(request.method());
    Set<Integer> statuses=new TreeSet<>(old==null?Set.of():old.statuses());if(response!=null)statuses.add((int)response.statusCode());
    Set<String> fields=new TreeSet<>(old==null?Set.of():old.fields());
    if(response!=null&&response.body().length()<=65536&&Objects.toString(response.headerValue("Content-Type"),"").toLowerCase(Locale.ROOT).contains("json"))try{
      Object value=MinerJson.parse(JsBodyDecoder.decode(response.body().getBytes(),response.headerValue("Content-Encoding"),response.headerValue("Content-Type")));collect(value,fields,0);
    }catch(Exception ignored){}
    rows.put(url,new Entry(Set.copyOf(methods),Set.copyOf(fields),Set.copyOf(statuses),old==null?1:old.count()+1));
    while(rows.size()>10000){rows.remove(rows.keySet().iterator().next());evicted++;}revision++;
  }
  private static void collect(Object value,Set<String> fields,int depth){if(depth>6||fields.size()>=128)return;if(value instanceof Map<?,?> map)for(var e:map.entrySet()){if(fields.size()>=128)return;String name=e.getKey().toString();if(name.length()<=128)fields.add(name);collect(e.getValue(),fields,depth+1);}else if(value instanceof List<?> list)for(int i=0;i<Math.min(list.size(),8);i++)collect(list.get(i),fields,depth+1);}
  synchronized Entry get(String url){return rows.get(key(url));}
  synchronized void clear(){rows.clear();revision++;}
  static String key(String url){try{java.net.URI u=java.net.URI.create(url);if(u.getHost()==null||!Set.of("http","https").contains(u.getScheme()))return null;return u.getScheme()+"://"+u.getHost().toLowerCase(Locale.ROOT)+":"+(u.getPort()<0?(u.getScheme().equals("https")?443:80):u.getPort())+Objects.toString(u.getRawPath(),"/");}catch(Exception ex){return null;}}
  static String state(JsAnalysis.Endpoint e,Entry observed){
    if(!e.complete())return e.evidence().contains("실행 페이지 미확정")?"실행 페이지 미확정":"동적 값 미해결";
    if(observed==null)return "JS에서만 발견";
    if(!JsMethodSelection.known(e.method()))return "메서드 미확정 · 호출 관찰";
    return observed.methods().contains(e.method())?"실제 호출":"메서드 충돌";
  }
  static List<String> reasons(JsAnalysis.Endpoint e,Entry seen){List<String> out=new ArrayList<>();String name=JsFindingGroups.priority(e);if(name.startsWith("우선 검토"))out.add(name.substring("우선 검토 · ".length()));
    if(seen!=null){if(!seen.methods().contains(e.method())&&JsMethodSelection.known(e.method()))out.add("분석/관찰 메서드 충돌");
      Set<String> sensitive=new TreeSet<>();for(String field:seen.fields())if(field.matches("(?i).*(?:email|phone|password|secret|token|ssn|주민|전화|이메일).*"))sensitive.add(field);if(!sensitive.isEmpty())out.add("응답 민감 필드 이름: "+String.join(", ",sensitive));
      if(out.size()>0)out.add("수집 패킷 "+seen.count()+"건의 근거");
    }return List.copyOf(out);}
}
