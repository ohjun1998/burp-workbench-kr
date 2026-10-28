package kr.workbench;

import java.util.*;

/** UI grouping preserves all call sites and chooses one explicit representative draft. */
final class JsFindingGroups {
  static int column(String source,int start){if(start<0||start>source.length())return 0;return start-source.lastIndexOf('\n',Math.max(0,start-1));}
  static String key(JsAnalysis.Endpoint endpoint){return endpoint.method()+" "+endpoint.url()+"|"+(endpoint.kind().contains("GraphQL")?endpoint.kind().substring(endpoint.kind().indexOf("GraphQL")):"");}
  static String priority(JsAnalysis.Endpoint endpoint){
    String value=(endpoint.url()+" "+endpoint.parameters()+" "+endpoint.bodyKeys()).toLowerCase(Locale.ROOT);
    if(value.matches("(?s).*(?:admin|permission|role|upload|export|import|callback|redirect|webhook).*"))return "우선 검토 · 권한/파일/외부 URL 관련 이름";
    if(value.matches("(?s).*(?:user[_-]?id|account[_-]?id|order[_-]?id|tenant|object[_-]?id|/users/|/orders/).*"))return "우선 검토 · 객체/계정 식별자";
    return "일반 검토";
  }
  static String evidence(JsAnalysis.Result result,JsAnalysis.Endpoint selected){
    StringJoiner sites=new StringJoiner("\n");
    for(var endpoint:result.endpoints())if(key(endpoint).equals(key(selected)))sites.add(endpoint.kind()+" · 줄 "+endpoint.line()+" · params "+endpoint.parameters()+" · body "+endpoint.bodyKeys());
    return priority(selected)+" · 이름 기반 후보, 취약점 확정 아님\n"+selected.evidence()+"\n"+sites;
  }
  static JsAnalysis.Result group(JsAnalysis.Result result){
    Map<String,JsAnalysis.Endpoint> representatives=new LinkedHashMap<>();
    Map<String,List<JsAnalysis.Endpoint>> sites=new LinkedHashMap<>();
    for(var endpoint:result.endpoints()){String key=key(endpoint);representatives.putIfAbsent(key,endpoint);sites.computeIfAbsent(key,k->new ArrayList<>()).add(endpoint);}
    List<JsAnalysis.Endpoint> endpoints=new ArrayList<>();Map<String,JsRequestDraft.Draft> drafts=new LinkedHashMap<>();
    for(var entry:representatives.entrySet()){
      var first=entry.getValue();var variants=sites.get(entry.getKey());StringJoiner provenance=new StringJoiner("; ");
      for(var site:variants)provenance.add(site.kind()+" @"+site.line()+" offset="+site.start()+" params="+site.parameters()+" body="+site.bodyKeys());
      var grouped=new JsAnalysis.Endpoint(first.url(),first.method(),first.kind(),union(variants,0),union(variants,1),union(variants,2),first.line(),first.start(),first.length(),first.complete(),first.evidence()+" · 발견 위치 "+variants.size()+"개 · "+provenance+" · 초안은 첫 발견 기준");
      endpoints.add(grouped);var draft=result.requestDrafts().get(JsAnalysis.draftKey(first));if(draft!=null)drafts.put(JsAnalysis.draftKey(grouped),draft);
    }
    return new JsAnalysis.Result(List.copyOf(endpoints),result.secrets(),result.status().replaceAll("URL/API \\d+","URL/API "+endpoints.size()),result.truncated(),result.miner(),Map.copyOf(drafts));
  }
  private static String union(List<JsAnalysis.Endpoint> sites,int field){Set<String> names=new LinkedHashSet<>();for(var site:sites){String text=field==0?site.parameters():field==1?site.headers():site.bodyKeys();for(String name:text.split(",\\s*"))if(!name.isBlank())names.add(name);}return String.join(", ",names);}
}
