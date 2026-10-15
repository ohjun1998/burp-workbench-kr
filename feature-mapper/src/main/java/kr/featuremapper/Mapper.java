package kr.featuremapper;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** Automatic feature assignment plus per-execution, per-session evidence. No network sends. */
public final class Mapper implements AutoCloseable {
    static final long LINK_WINDOW=120_000, RUN_GAP=30_000, BROWSER_WINDOW=2500;
    record Input(long time,String tool,String method,String url,int status,byte[] request,byte[] response,
                 byte[] requestBody,byte[] responseBody,Map<String,String> requestHeaders,Map<String,String> responseHeaders){}
    static final class Feature {
        final String id,key,origin,entity;String name,state="미점검",note="";boolean renamed;
        Feature(String id,String key,String origin,String entity,String name){this.id=id;this.key=key;this.origin=origin;this.entity=entity;this.name=name;}
    }
    static final class Packet {
        final long id,time;final String tool,method,url,origin,signature,session,entity,operation,bodyHash,page;final int status;final boolean common,partial;final PacketStore.Ref raw;
        final Map<String,String> links=new LinkedHashMap<>();final List<Long> parents=new ArrayList<>();final List<String> reasons=new ArrayList<>();
        String primary,run,confidence="중간",browserAction="",browserTab="",browserFrame="";
        Packet(long id,Input i,String signature,String session,String entity,String operation,boolean common,PacketStore.Ref raw){
            this.id=id;time=i.time;tool=i.tool;method=i.method;url=i.url;origin=Signals.origin(url);this.signature=signature;this.session=session;this.entity=entity;this.operation=operation;status=i.status;this.common=common;this.raw=raw;bodyHash=Signals.digest(i.requestBody);page=Signals.safePage(i.requestHeaders.getOrDefault("referer",""));partial=i.requestBody.length>Signals.ANALYSIS_BYTES||i.responseBody.length>Signals.ANALYSIS_BYTES;
        }
        Packet(Map<String,Object> m){id=num(m,"id");time=num(m,"time");tool=str(m,"tool");method=str(m,"method");url=str(m,"url");origin=Signals.origin(url);signature=str(m,"signature");session=str(m,"session");entity=str(m,"entity");operation=str(m,"operation");status=(int)num(m,"status");common=Boolean.TRUE.equals(m.get("common"));partial=Boolean.TRUE.equals(m.get("partial"));bodyHash=str(m,"bodyHash");page=str(m,"page");raw=new PacketStore.Ref(num(m,"offset"),(int)num(m,"requestBytes"),(int)num(m,"responseBytes"));primary=str(m,"primary");run=str(m,"run");confidence=str(m,"confidence");browserAction=str(m,"browserAction");browserTab=str(m,"browserTab");browserFrame=str(m,"browserFrame");((Map<?,?>)m.get("links")).forEach((k,v)->links.put(String.valueOf(k),String.valueOf(v)));for(Object v:(List<?>)m.get("parents"))parents.add(((Number)v).longValue());for(Object v:(List<?>)m.get("reasons"))reasons.add(String.valueOf(v));}
        Map<String,Object> json(){Map<String,Object> m=new LinkedHashMap<>();m.put("id",id);m.put("time",time);m.put("tool",tool);m.put("method",method);m.put("url",url);m.put("signature",signature);m.put("session",session);m.put("entity",entity);m.put("operation",operation);m.put("status",status);m.put("common",common);m.put("partial",partial);m.put("bodyHash",bodyHash);m.put("page",page);m.put("offset",raw.offset());m.put("requestBytes",raw.requestBytes());m.put("responseBytes",raw.responseBytes());m.put("primary",primary);m.put("run",run);m.put("confidence",confidence);m.put("browserAction",browserAction);m.put("browserTab",browserTab);m.put("browserFrame",browserFrame);m.put("links",links);m.put("parents",parents);m.put("reasons",reasons);return m;}
    }
    record Producer(long packet,String field,long time,String entity,String page){}
    private final PacketStore store;
    private String salt=UUID.randomUUID().toString();
    final LinkedHashMap<String,Feature> features=new LinkedHashMap<>();
    final LinkedHashMap<Long,Packet> packets=new LinkedHashMap<>();
    final Map<String,String> corrections=new LinkedHashMap<>();
    final Map<String,String> apiNames=new LinkedHashMap<>();
    final Map<String,Set<String>> knownIds=new HashMap<>();
    private final Map<String,List<Producer>> producers=new HashMap<>();
    private final Map<String,Packet> recentRuns=new HashMap<>();
    private final Deque<Packet> recentPackets=new ArrayDeque<>();
    private final List<Map<String,Object>> browserEvents=new ArrayList<>();
    private long sequence;
    public Mapper()throws IOException{store=new PacketStore();}

    synchronized Packet accept(Input i)throws IOException{
        String origin=Signals.origin(i.url),path=URI.create(i.url).getRawPath();if(path==null||path.isEmpty())path="/";
        Map<String,String> fields=new LinkedHashMap<>(Signals.fields(i.requestBody,i.requestHeaders));
        String query=URI.create(i.url).getRawQuery();if(query!=null)Signals.form(query).forEach(fields::putIfAbsent);
        String template=Signals.template(path,knownIds.getOrDefault(origin,Set.of()));
        String operation=Signals.operation(i.method,template,fields);
        if(template.endsWith("graphql")&&fields.getOrDefault("operationName","").isBlank()){
            String gql=fields.getOrDefault("query","");java.util.regex.Matcher match=java.util.regex.Pattern.compile("(?:query|mutation|subscription)\\s+([A-Za-z_][A-Za-z_0-9]*)|\\{\\s*([A-Za-z_][A-Za-z_0-9]*)").matcher(gql);if(match.find())operation=match.group(1)!=null?match.group(1):match.group(2);
        }
        if(template.endsWith("graphql")&&Signals.json(i.requestBody) instanceof List<?> batch){List<String> ops=new ArrayList<>();for(Object entry:batch)if(entry instanceof Map<?,?> m)ops.add(String.valueOf(m.getOrDefault("operationName",null)));Collections.sort(ops);operation="batch:"+String.join("+",ops);}
        String entity=Signals.entity(template);if(template.endsWith("graphql"))entity="GraphQL";
        String signature=i.method+" "+template+" | "+operation;
        String session=Signals.session(origin,i.requestHeaders,salt);
        Packet p=new Packet(++sequence,i,signature,session,entity,operation,Signals.common(path,i.responseHeaders),store.append(i.request,i.response));
        String key=origin+"|"+(p.common?"공통 요청":signature);
        Feature f=feature(key,origin,p.common?"공통 요청":entity,p.common?"공통 요청":apiNames.getOrDefault(origin+"|"+i.method+" "+template,entity+" / "+operation+" · "+template));
        p.primary=f.id;
        String correction=corrections.get(correctionKey(p));if(correction!=null&&features.containsKey(correction))p.primary=correction;
        String runKey=origin+"|"+session+"|"+p.page+"|"+p.primary;
        Packet prev=recentRuns.get(runKey);p.run=prev!=null&&i.time>=prev.time&&i.time-prev.time<RUN_GAP?prev.run:"실행-"+p.id;
        p.links.put(p.primary,p.run);p.reasons.add(p.common?"정적 자원·공통 경로":"메서드·URL 구조·동작: "+signature);
        if(correction!=null){p.confidence="높음";p.reasons.add("프로젝트에 저장한 분류 수정 규칙");}
        if(p.partial)p.reasons.add("원본 전체 보관 · 512KiB를 넘는 본문은 구조 분석 생략");
        packets.put(p.id,p);recentPackets.addLast(p);recentRuns.put(runKey,p);
        if(!p.common)connect(p,fields,path);
        Object responseJson=Signals.json(i.responseBody);
        if(responseJson instanceof Map<?,?> responseMap&&responseMap.get("paths") instanceof Map<?,?> paths)discoverApiNames(origin,paths);
        if(i.status>=200&&i.status<300&&!p.common&&Set.of("POST","PUT","PATCH").contains(i.method)){
            for(var e:Signals.leaves(responseJson).entrySet())if(Signals.identifier(e.getKey(),e.getValue())){
                knownIds.computeIfAbsent(origin,k->new LinkedHashSet<>()).add(e.getValue());
                String index=index(p,e.getValue());producers.computeIfAbsent(index,k->new ArrayList<>()).add(new Producer(p.id,e.getKey(),p.time,p.entity,p.page));
            }
        }
        applyBrowserEvents();prune(i.time);return p;
    }
    private Feature feature(String key,String origin,String entity,String name){String id=Signals.digest(key).substring(0,24);return features.computeIfAbsent(id,k->new Feature(k,key,origin,entity,Signals.clean(name,240)));}
    private String index(Packet p,String value){return p.origin+"|"+p.session+"|"+Signals.digest(value);}
    private void connect(Packet p,Map<String,String> fields,String path){
        Map<String,String> wanted=new LinkedHashMap<>();for(var e:fields.entrySet())if(Signals.identifier(e.getKey(),e.getValue()))wanted.put(e.getKey(),e.getValue());
        for(String segment:path.split("/"))if(!segment.isEmpty())wanted.put("$path:"+segment,segment);
        Set<Long> matched=new LinkedHashSet<>();
        for(var e:wanted.entrySet()){
            List<Producer> candidates=producers.getOrDefault(index(p,e.getValue()),List.of()).stream().filter(v->p.time>=v.time&&p.time-v.time<LINK_WINDOW).filter(v->p.page.isBlank()||v.page.isBlank()||p.page.equals(v.page)).filter(v->e.getKey().startsWith("$path:")?v.entity.equals(p.entity):relatedField(e.getKey(),v)).toList();
            Set<Long> unique=new HashSet<>();candidates.forEach(v->unique.add(v.packet));
            if(unique.size()==1)matched.add(unique.iterator().next());else if(unique.size()>1)p.reasons.add("동일 ID의 생산 요청이 여러 개라 연결 보류");
        }
        for(long parentId:matched){Packet parent=packets.get(parentId);p.parents.add(parentId);p.reasons.add("응답 ID → 요청 값 연결: 패킷 #"+parentId);p.confidence="높음";
            attachAncestors(parent,p.primary,p.run,0);
            if(parent.entity.equals(p.entity))for(var membership:parent.links.entrySet())p.links.putIfAbsent(membership.getKey(),membership.getValue());
        }
    }
    private static boolean relatedField(String consumer,Producer v){String a=consumer.substring(consumer.lastIndexOf('.')+1).toLowerCase(Locale.ROOT),b=v.field.substring(v.field.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);if(a.equals(b)&&!a.equals("id"))return true;String family=a.replaceAll("(uuid|id|key)$","");String expected=switch(v.entity){case "파일"->"file|attachment";case "게시판"->"post|article|board";case "회원"->"user|member";case "주문"->"order";default->java.util.regex.Pattern.quote(v.entity.toLowerCase(Locale.ROOT));};return b.equals("id")&&family.matches(expected);}
    private void attachAncestors(Packet p,String feature,String run,int depth){if(depth>8)return;p.links.putIfAbsent(feature,run);for(long parent:p.parents)if(packets.containsKey(parent))attachAncestors(packets.get(parent),feature,run,depth+1);}
    private void discoverApiNames(String origin,Map<?,?> paths){for(var entry:paths.entrySet()){if(!(entry.getValue() instanceof Map<?,?> methods))continue;String template=String.valueOf(entry.getKey()).replaceAll("\\{[^}]+}","{id}");for(var method:methods.entrySet())if(method.getValue() instanceof Map<?,?> op){Object name=op.get("summary");if(name==null)name=op.get("operationId");if(name!=null)apiNames.put(origin+"|"+String.valueOf(method.getKey()).toUpperCase(Locale.ROOT)+" "+template,Signals.clean(String.valueOf(name),200));}}}
    synchronized void browser(Map<String,Object> event){
        if(!"network".equals(str(event,"type")))throw new IllegalArgumentException("network 이벤트만 지원합니다.");
        Signals.origin(str(event,"url"));if(str(event,"actionId").isBlank()||str(event,"label").isBlank())return;
        long now=System.currentTimeMillis();if(Math.abs(num(event,"time")-now)>LINK_WINDOW)throw new IllegalArgumentException("브라우저 이벤트 시각이 만료됐습니다.");
        Map<String,Object> clean=new LinkedHashMap<>();for(String key:List.of("type","url","method","bodyHash","actionId","label","page","title","tab","frame"))clean.put(key,Signals.clean(str(event,key),key.equals("url")?8192:240));clean.put("time",num(event,"time"));
        browserEvents.removeIf(e->str(e,"tab").equals(str(clean,"tab"))&&str(e,"frame").equals(str(clean,"frame"))&&str(e,"actionId").equals(str(clean,"actionId"))&&str(e,"url").equals(str(clean,"url"))&&num(e,"time")==num(clean,"time"));browserEvents.add(clean);prune(now);applyBrowserEvents();
    }
    private void applyBrowserEvents(){
        for(Map<String,Object> e:browserEvents){List<Packet> candidates=recentPackets.stream().filter(p->p.tool.equalsIgnoreCase("PROXY")&&!p.common&&p.method.equals(str(e,"method"))&&p.url.equals(str(e,"url"))&&Math.abs(p.time-num(e,"time"))<BROWSER_WINDOW).filter(p->str(e,"bodyHash").isBlank()||p.bodyHash.equals(str(e,"bodyHash"))).toList();
            if(candidates.size()!=1){for(Packet candidate:candidates)unmapAmbiguousBrowser(candidate);continue;}Packet p=candidates.get(0);
            long alternatives=browserEvents.stream().filter(other->p.method.equals(str(other,"method"))&&p.url.equals(str(other,"url"))&&Math.abs(p.time-num(other,"time"))<BROWSER_WINDOW).filter(other->str(other,"bodyHash").isBlank()||p.bodyHash.equals(str(other,"bodyHash"))).map(other->str(other,"tab")+":"+str(other,"frame")+":"+str(other,"actionId")).distinct().count();
            if(alternatives!=1){unmapAmbiguousBrowser(p);continue;}if(!p.browserAction.isEmpty())continue;
            String page=Signals.safePage(str(e,"page"));if(page.isBlank()||!Signals.origin(page).equals(p.origin))continue;
            String label=Signals.clean(str(e,"label"),80);String route=Signals.template(URI.create(page).getRawPath(),knownIds.getOrDefault(p.origin,Set.of()));
            Feature f=feature(p.origin+"|browser|"+route+"|"+label,p.origin,"화면 기능",label+" · "+route);
            p.browserAction=str(e,"actionId");p.browserTab=str(e,"tab");p.browserFrame=str(e,"frame");if(!corrections.containsKey(correctionKey(p)))p.primary=f.id;p.run="브라우저-"+Signals.digest(p.origin+p.session+p.browserTab+p.browserFrame+p.browserAction).substring(0,16);p.links.put(f.id,p.run);p.reasons.add("브라우저 화면·동작: "+label+" (탭 "+p.browserTab+" / 프레임 "+p.browserFrame+")");p.confidence=p.parents.isEmpty()?"중간":"높음";
            // Temporal browser attribution is evidence of context, not proof of JS causality.
            attachAncestors(p,f.id,p.run,0);
        }
    }
    private void unmapAmbiguousBrowser(Packet p){
        if(p.browserAction.isBlank())return;
        String previousRun=p.run;for(Packet item:recentPackets)item.links.entrySet().removeIf(e->e.getValue().equals(previousRun)&&features.get(e.getKey()).key.contains("|browser|"));
        String intrinsic=Signals.digest(p.origin+"|"+(p.common?"공통 요청":p.signature)).substring(0,24);p.primary=corrections.getOrDefault(correctionKey(p),intrinsic);p.run=p.links.getOrDefault(p.primary,"실행-"+p.id);p.browserAction="";p.browserTab="";p.browserFrame="";p.confidence="낮음";p.reasons.removeIf(x->x.startsWith("브라우저 화면·동작:"));p.reasons.add("동시 동일 요청·브라우저 동작 중복으로 화면 매핑 보류");
    }
    private void prune(long time){while(!recentPackets.isEmpty()&&recentPackets.peekFirst().time<time-LINK_WINDOW)recentPackets.removeFirst();browserEvents.removeIf(e->num(e,"time")<time-LINK_WINDOW);if(browserEvents.size()>2000)browserEvents.subList(0,browserEvents.size()-2000).clear();producers.values().forEach(a->a.removeIf(p->p.time<time-LINK_WINDOW));producers.entrySet().removeIf(e->e.getValue().isEmpty());recentRuns.entrySet().removeIf(e->e.getValue().time<time-LINK_WINDOW);}
    private static String correctionKey(Packet p){return p.origin+"|"+p.signature+"|"+p.page;}
    synchronized void move(long packetId,String featureId){if(!features.containsKey(featureId))throw new IllegalArgumentException("기능이 없습니다.");Packet p=packets.get(packetId);if(p==null)throw new IllegalArgumentException("패킷이 없습니다.");if(!features.get(featureId).origin.equals(p.origin))throw new IllegalArgumentException("다른 사이트의 기능으로 이동할 수 없습니다.");p.primary=featureId;p.links.put(featureId,p.run);p.confidence="높음";p.reasons.add("사용자가 분류 수정");corrections.put(correctionKey(p),featureId);}
    synchronized void edit(String id,String name,String state,String note){Feature f=features.get(id);if(f==null)throw new IllegalArgumentException("기능 없음");f.name=Signals.clean(name,240);f.state=state;f.note=note;f.renamed=true;}
    synchronized List<Feature> featureList(){return List.copyOf(features.values());}
    synchronized List<Packet> packetList(String feature){return packets.values().stream().filter(p->feature==null||p.links.containsKey(feature)).toList();}
    synchronized byte[][] raw(long id)throws IOException{Packet p=packets.get(id);if(p==null)throw new IOException("패킷 없음");return store.read(p.raw);}
    synchronized long count(){return packets.size();}
    synchronized long diskBytes()throws IOException{return store.bytes();}
    PacketStore store(){return store;}
    synchronized Map<String,Object> metadata(){Map<String,Object> m=new LinkedHashMap<>();m.put("format","feature-mapper-kr");m.put("version",1);m.put("salt",salt);m.put("sequence",sequence);List<Object> fs=new ArrayList<>();for(Feature f:features.values())fs.add(Map.of("id",f.id,"key",f.key,"origin",f.origin,"entity",f.entity,"name",f.name,"state",f.state,"note",f.note,"renamed",f.renamed));m.put("features",fs);m.put("packets",packets.values().stream().map(Packet::json).toList());m.put("corrections",corrections);m.put("apiNames",apiNames);m.put("knownIds",knownIds);return m;}
    @SuppressWarnings("unchecked") synchronized void restoreMetadata(Map<String,Object> m)throws IOException{
        if(!"feature-mapper-kr".equals(m.get("format"))||num(m,"version")!=1)throw new IOException("지원하지 않는 프로젝트 형식입니다.");
        salt=str(m,"salt");sequence=num(m,"sequence");
        for(Object x:(List<?>)m.get("features")){Map<String,Object> a=(Map<String,Object>)x;Feature f=new Feature(str(a,"id"),str(a,"key"),str(a,"origin"),str(a,"entity"),str(a,"name"));f.state=str(a,"state");f.note=str(a,"note");f.renamed=Boolean.TRUE.equals(a.get("renamed"));if(features.put(f.id,f)!=null)throw new IOException("중복 기능 ID");}
        for(Object x:(List<?>)m.get("packets")){Packet p=new Packet((Map<String,Object>)x);store.validate(p.raw);if(p.id<1||p.id>sequence||!features.containsKey(p.primary)||!features.keySet().containsAll(p.links.keySet())||packets.put(p.id,p)!=null)throw new IOException("잘못된 패킷 참조");}
        for(Packet p:packets.values())if(!packets.keySet().containsAll(p.parents)||p.parents.stream().anyMatch(id->id>=p.id))throw new IOException("잘못된 패킷 연결");
        ((Map<?,?>)m.get("corrections")).forEach((k,v)->corrections.put(String.valueOf(k),String.valueOf(v)));((Map<?,?>)m.get("apiNames")).forEach((k,v)->apiNames.put(String.valueOf(k),String.valueOf(v)));((Map<?,?>)m.get("knownIds")).forEach((k,v)->{Set<String> ids=new LinkedHashSet<>();for(Object id:(List<?>)v)ids.add(String.valueOf(id));knownIds.put(String.valueOf(k),ids);});
    }
    static String str(Map<?,?> m,String k){return m.get(k)==null?"":String.valueOf(m.get(k));}
    static long num(Map<?,?> m,String k){Object v=m.get(k);if(!(v instanceof Number n))throw new IllegalArgumentException("숫자 필드 없음: "+k);return n.longValue();}
    public synchronized void close()throws IOException{store.close();}
}
