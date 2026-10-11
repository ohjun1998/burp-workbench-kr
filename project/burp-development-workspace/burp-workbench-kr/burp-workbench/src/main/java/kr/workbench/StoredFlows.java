package kr.workbench;
import java.util.*;
/** User registered marker metadata only; no payload persistence or browser execution. */
final class StoredFlows {
 record Flow(String marker,String writeUrl,String readUrl,String state,int status){}
 private final LinkedHashSet<String> markers=new LinkedHashSet<>();private final Map<String,String> writes=new HashMap<>();private final LinkedHashMap<String,Flow> flows=new LinkedHashMap<>();
 synchronized void register(String marker){if(!marker.matches("(?:WB|PR)_[A-Za-z0-9_]{6,60}"))throw new IllegalArgumentException("WB_ 또는 PR_로 시작하는 6~60자 고유 마커");if(markers.size()>=20&&!markers.contains(marker))throw new IllegalArgumentException("마커 최대 20개");markers.add(marker);}
 synchronized void observe(String url,String method,String requestBody,int status,String type,String responseBody){String safe=ActivityTracker.safeUrl(url);if(safe==null)return;for(String marker:markers){String key=ApiInventory.origin(safe)+"|"+marker;if(!Core.readMethod(method)&&requestBody.contains(marker)){if(status>=200&&status<300)writes.put(key,safe);put(key+"|write",new Flow(marker,safe,"",status>=200&&status<300?"쓰기 성공 응답 관측 · 저장 미확정":"쓰기 실패/차단",status));}if(Core.readMethod(method)&&responseBody.contains(marker)&&writes.containsKey(key)){int index=responseBody.indexOf(marker);String around=responseBody.substring(Math.max(0,index-80),Math.min(responseBody.length(),index+80));String state=type.toLowerCase(Locale.ROOT).contains("html")?(around.contains("&lt;")?"HTML 문자 표시 후보 · 실행 미검증":"HTML 조회 출력 · 실행 미검증"):"JSON/문자열 조회 · 화면 출력 미검증";put(key+"|"+safe,new Flow(marker,writes.get(key),safe,state,status));}}}
 private void put(String key,Flow flow){if(flows.size()>=500&&!flows.containsKey(key))flows.remove(flows.keySet().iterator().next());flows.put(key,flow);}
 synchronized List<Flow> views(){return List.copyOf(flows.values());}synchronized void clear(){markers.clear();writes.clear();flows.clear();}
}
