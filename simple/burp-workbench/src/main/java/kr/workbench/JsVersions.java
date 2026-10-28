package kr.workbench;

import java.time.Instant;
import java.util.*;

/** Version differences contain methods and field names only, not draft or credential values. */
final class JsVersions {
  record Signature(String methods,String query,String headers,String body){}
  record Version(String source,String fingerprint,String time,Map<String,Signature> endpoints,Set<String> secrets){}
  record Change(String url,String state,String methods,String fields){}
  final LinkedHashMap<String,Version> versions=new LinkedHashMap<>();
  long evicted;
  synchronized void record(String source,String hash,JsAnalysis.Result result){recordAt(source,hash,result,Instant.now().toString());}
  synchronized void recordAt(String source,String hash,JsAnalysis.Result result,String when){
    if(source.isBlank()||hash==null||hash.isBlank())return;String base=base(source),key=base+"#"+hash;
    if(versions.containsKey(key))return;
    Map<String,List<JsAnalysis.Endpoint>> byUrl=new TreeMap<>();for(var e:result.endpoints())byUrl.computeIfAbsent(e.url()+(e.kind().contains("GraphQL")?" | "+e.kind().substring(e.kind().indexOf("GraphQL")):""),k->new ArrayList<>()).add(e);
    Map<String,Signature> signatures=new LinkedHashMap<>();for(var entry:byUrl.entrySet()){Set<String> verbs=new TreeSet<>(),params=new TreeSet<>(),headers=new TreeSet<>(),body=new TreeSet<>();for(var e:entry.getValue()){verbs.add(e.method());names(params,e.parameters());names(headers,e.headers());names(body,e.bodyKeys());}signatures.put(entry.getKey(),new Signature(String.join(",",verbs),String.join(",",params),String.join(",",headers),String.join(",",body)));}
    versions.put(key,new Version(base,hash,when,Map.copyOf(signatures),result.secrets().stream().map(JsAnalysis.Secret::fingerprint).collect(java.util.stream.Collectors.toSet())));
    while(versions.size()>64||weight()>2*1024*1024){versions.remove(versions.keySet().iterator().next());evicted++;}
  }
  static void names(Set<String> out,String text){for(String name:text.split(",\\s*"))if(!name.isBlank())out.add(name);}
  synchronized long weight(){long n=0;for(var v:versions.values()){n+=256L+2L*(v.source().length()+v.fingerprint().length());for(var e:v.endpoints().entrySet())n+=128L+2L*(e.getKey().length()+e.getValue().toString().length());n+=v.secrets().size()*64L;}return n;}
  synchronized List<Version> forSource(String source){return versions.values().stream().filter(v->v.source().equals(base(source))).sorted(Comparator.comparing(Version::time)).toList();}
  synchronized Map<String,String> latestChanges(String source){var list=forSource(source);if(list.size()<2)return Map.of();Map<String,String> labels=new HashMap<>();for(var c:compare(list.get(list.size()-2),list.get(list.size()-1)))labels.put(c.url(),c.state());return Map.copyOf(labels);}
  synchronized Map<String,String> changesFor(String source,String hash){var list=forSource(source);int index=-1;for(int i=0;i<list.size();i++)if(list.get(i).fingerprint().equals(hash))index=i;if(index<1)return Map.of();Map<String,String> labels=new HashMap<>();for(var change:compare(list.get(index-1),list.get(index)))labels.put(change.url(),change.state());return Map.copyOf(labels);}
  static List<Change> compare(Version before,Version after){
    if(!before.source().equals(after.source()))throw new IllegalArgumentException("같은 출처의 버전을 선택하세요.");
    Set<String> urls=new TreeSet<>(before.endpoints().keySet());urls.addAll(after.endpoints().keySet());List<Change> out=new ArrayList<>();
    for(String url:urls){Signature a=before.endpoints().get(url),b=after.endpoints().get(url);String state=a==null?"신규":b==null?"삭제":!a.methods().equals(b.methods())?"메서드 변경":!a.equals(b)?"입력 변경":"동일";
      out.add(new Change(url,state,a==null?b.methods():b==null?a.methods():a.methods().equals(b.methods())?b.methods():a.methods()+" → "+b.methods(),a!=null&&b!=null&&!a.equals(b)?"쿼리/헤더/본문 키 비교":""));}
    return List.copyOf(out);
  }
  static String base(String source){return JsAnalysisPanel.safeSource(source.split("#wb-version=",2)[0]);}
  synchronized void clear(){versions.clear();}
}
