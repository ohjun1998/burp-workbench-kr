package kr.workbench;

import java.time.Instant;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded marker observations. Response text is neither persisted nor proof of browser execution. */
final class StoredFlows {
 static final int MAX_WRITES=100, MAX_FLOWS=500;
 record Source(String id,String method,String url,int status) {}
 record Flow(String id,String marker,String writeUrl,String readUrl,String state,int status,
             boolean writeAccepted,boolean readbackObserved,boolean htmlObserved,boolean escapedCandidate,
             String browserEvidence,String browserRecordedAt,Source writeSource,Source readSource) {}
 private final LinkedHashSet<String> markers=new LinkedHashSet<>();
 private record Write(Source source,String target,int redirects) {}
 private final LinkedHashMap<String,Write> writes=new LinkedHashMap<>();
 private final LinkedHashMap<String,Flow> flows=new LinkedHashMap<>();
 private long nextSource,nextFlow;

 synchronized void register(String marker){
  if(marker==null||!marker.matches("(?:WB|PR)_[A-Za-z0-9_]{6,60}"))throw new IllegalArgumentException("WB_ 또는 PR_로 시작하는 6~60자 고유 마커");
  if(markers.size()>=20&&!markers.contains(marker))throw new IllegalArgumentException("마커 최대 20개");
  markers.add(marker);
 }

 /** Returns a source ID only when this request contributed an observation. */
 synchronized String observe(String url,String method,String requestBody,int status,String type,String responseBody){
  return observe(url,method,requestBody,status,type,responseBody,"");
 }

 synchronized String observe(String url,String method,String requestBody,int status,String type,String responseBody,String location){
  String safe=ActivityTracker.safeUrl(url);
  if(safe==null||method==null||!method.matches("[A-Za-z]{1,20}"))return "";
  String verb=method.toUpperCase(Locale.ROOT),body=Objects.toString(requestBody,""),response=Objects.toString(responseBody,"");
  Source source=new Source("SF-P"+(++nextSource),verb,safe,status);
  boolean contributed=false;
  for(String marker:markers){
   String key=ApiInventory.origin(safe)+"|"+marker;
   if(!Core.readMethod(verb)&&body.contains(marker)){
    // A new attempt must not inherit earlier successful storage or browser evidence.
    removeGroup(key);boolean accepted=status>=200&&status<300;
    String target=redirectTarget(url,status,location,marker);
    if(accepted||!target.isEmpty()){
     if(writes.size()>=MAX_WRITES)removeGroup(writes.keySet().iterator().next());
     writes.put(key,new Write(source,accepted?"":target,0));
    }
    String state=accepted?"쓰기 성공 응답 관측 · 저장 미확정":status>=300&&status<400?"리다이렉트 관측 · 후속 조회/저장 미확정":"쓰기 실패/차단 · 저장 미확정";
    put(key+"|write",new Flow("SF-F"+(++nextFlow),marker,safe,"",state,status,accepted,false,false,false,"","",source,null));
    contributed=true;
   }
   // OPTIONS cannot establish that a stored object was retrieved.
   Write pending=writes.get(key);
   if(pending!=null&&!pending.target().isEmpty()&&(verb.equals("GET")||verb.equals("HEAD"))&&pending.target().equals(safe)){
    String next=redirectTarget(url,status,location,marker);
    if(!next.isEmpty()&&pending.redirects()<3){writes.put(key,new Write(pending.source(),next,pending.redirects()+1));contributed=true;continue;}
   }
   if((verb.equals("GET")||verb.equals("HEAD"))&&response.contains(marker)&&pending!=null
       &&(pending.target().isEmpty()||pending.target().equals(safe)&&!echoedMarker(url,marker))){
    boolean readback=status>=200&&status<300;
    String media=Objects.toString(type,"").split(";",2)[0].strip().toLowerCase(Locale.ROOT);
    boolean html=readback&&(media.equals("text/html")||media.equals("application/xhtml+xml"));
    int index=response.indexOf(marker);
    String around=response.substring(Math.max(0,index-80),Math.min(response.length(),index+marker.length()+80));
    boolean escaped=html&&(around.contains("&lt;")||around.contains("&#60;")||around.toLowerCase(Locale.ROOT).contains("&#x3c;"));
    String state=!readback?"조회 오류 응답의 마커 · 저장 확인 보류":html?(escaped?"HTML 문자 표시 후보 · 실행 미검증":"HTML 조회 출력 · 실행 미검증"):"JSON/문자열 조회 · 화면 출력 미검증";
    String flowKey=key+"|read|"+safe;Flow previous=flows.get(flowKey);
    Source write=pending.source();boolean writeAccepted=write.status()>=200&&write.status()<300;
    if(!writeAccepted)state="리다이렉트 후 "+state+" · 쓰기 2xx 미관측";
    // A repeated URL is a new observation; old manual evidence is not carried forward.
    put(flowKey,new Flow(previous==null?"SF-F"+(++nextFlow):previous.id(),marker,write.url(),safe,state,status,writeAccepted,readback,html,escaped,"","",write,source));
    contributed=true;
   }
  }
  return contributed?source.id():"";
 }

 private static boolean echoedMarker(String url,String marker){
  try{return URLDecoder.decode(url,StandardCharsets.UTF_8).contains(marker);}catch(IllegalArgumentException error){return true;}
 }
 private static String redirectTarget(String from,int status,String location,String marker){
  if(!Set.of(301,302,303,307,308).contains(status)||location==null||location.isBlank()||location.length()>2048)return "";
  try{
   URI target=URI.create(from).resolve(location);
   if(target.getUserInfo()!=null||!ApiInventory.origin(from).equals(ApiInventory.origin(target.toString()))||echoedMarker(target.toString(),marker))return "";
   String safe=ActivityTracker.safeUrl(target.toString());return safe==null?"":safe;
  }catch(IllegalArgumentException error){return "";}
 }

 static String writeState(Flow flow){
  if(flow.writeAccepted())return "2xx 관측 · 저장 미확정";
  if(flow.writeSource()!=null&&flow.writeSource().status()>=300&&flow.writeSource().status()<400)return "리다이렉트 · 쓰기 성공 미확정";
  return "실패/차단";
 }

 synchronized void recordBrowserEvidence(String flowId,String readSourceId,String sha256){
  if(sha256==null||!sha256.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("브라우저 이미지 SHA-256 필요");
  var entry=find(flowId);Flow f=entry.getValue();
  if(!f.htmlObserved()||f.readSource()==null||!f.readSource().id().equals(readSourceId))throw new IllegalArgumentException("선택 조회가 변경되었거나 성공 HTML 관측이 없습니다. 다시 선택하세요.");
  flows.put(entry.getKey(),new Flow(f.id(),f.marker(),f.writeUrl(),f.readUrl(),"브라우저 실행 수동 확인 · 자동 검증 아님",f.status(),f.writeAccepted(),f.readbackObserved(),f.htmlObserved(),f.escapedCandidate(),sha256.toLowerCase(Locale.ROOT),Instant.now().toString(),f.writeSource(),f.readSource()));
 }

 synchronized void clearBrowserEvidence(String flowId){
  var entry=find(flowId);Flow f=entry.getValue();
  String state=f.htmlObserved()?(f.escapedCandidate()?"HTML 문자 표시 후보 · 실행 미검증":"HTML 조회 출력 · 실행 미검증"):f.state();
  flows.put(entry.getKey(),new Flow(f.id(),f.marker(),f.writeUrl(),f.readUrl(),state,f.status(),f.writeAccepted(),f.readbackObserved(),f.htmlObserved(),f.escapedCandidate(),"","",f.writeSource(),f.readSource()));
 }
 private Map.Entry<String,Flow> find(String id){return flows.entrySet().stream().filter(e->e.getValue().id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("관측이 만료되었습니다. 다시 선택하세요."));}
 private void removeGroup(String key){writes.remove(key);flows.keySet().removeIf(k->k.startsWith(key+"|"));}
 private void put(String key,Flow flow){if(flows.size()>=MAX_FLOWS&&!flows.containsKey(key))flows.remove(flows.keySet().iterator().next());flows.put(key,flow);}
 synchronized List<Flow> views(){return List.copyOf(flows.values());}
 synchronized void clear(){markers.clear();writes.clear();flows.clear();}
 static String describe(Flow f){
  return "흐름 "+f.id()+" · 마커 "+f.marker()+"\n쓰기 성공 응답: "+(f.writeAccepted()?"관측":"미확인")+" (저장 자체의 증명 아님)\n조회 재출력: "+(f.readbackObserved()?"2xx 응답에서 관측":"미확인")+"\nHTML 응답 출력: "+(f.htmlObserved()?(f.escapedCandidate()?"이스케이프 문자 후보 · 안전 확정 아님":"관측 · 실행 미검증"):"미확인")+"\n브라우저 실행: "+(f.browserEvidence().isEmpty()?"미검증":"사용자 수동 확인 · "+f.browserRecordedAt()+"\n이미지 SHA-256: "+f.browserEvidence())+"\n쓰기 원본: "+sourceLabel(f.writeSource())+"\n조회 원본: "+sourceLabel(f.readSource())+"\n같은 Origin/마커에 따른 연관 후보입니다. 서버 영구 저장·사용자 영향은 별도 확인하세요. 원문/쿼리 값/인증값은 이 요약에 포함하지 않습니다.";
 }
 private static String sourceLabel(Source source){return source==null?"없음":source.id()+" · "+source.method()+" "+source.url()+" · HTTP "+source.status();}
}
