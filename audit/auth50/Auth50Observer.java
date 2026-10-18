package kr.workbench;

import burp.api.montoya.http.message.requests.HttpRequest;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent loopback read-back around actual transport; no response or production verdict is changed. */
final class Auth50Observer {
    record Before(String caseId,String actor,String profile,String mode,int featureId,Map<String,Object> state){}
    final String base;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    final Map<String,Map<String,Map<String,String>>> credentials=new ConcurrentHashMap<>();
    final Map<Integer,Before> pending=new ConcurrentHashMap<>();
    final Map<String,List<Map<String,Object>>> observations=new ConcurrentHashMap<>();
    final AtomicInteger requests=new AtomicInteger();
    Auth50Observer(String base){this.base=base;URI u=URI.create(base);if(!u.getHost().equals("127.0.0.1")||!u.getScheme().equals("http"))throw new IllegalArgumentException("Loopback observer only");}
    void register(String profile,List<Map<String,String>> bundles){Map<String,Map<String,String>> all=new LinkedHashMap<>();for(int i=0;i<3;i++)all.put(Auth50Cases.ACTORS.get(i),bundles.get(i));all.put("anonymous",Map.of());credentials.put(profile,all);}
    @SuppressWarnings("unchecked") Map<String,Object> state(String profile,String mode,int fid)throws Exception{
        var q=java.net.http.HttpRequest.newBuilder(URI.create(base+"/api/auth-lab/"+profile+"/bench/inspect/"+fid+"?mode="+mode)).timeout(Duration.ofSeconds(8)).GET();credentials.get(profile).get("admin").forEach(q::header);requests.incrementAndGet();var r=client.send(q.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));if(r.statusCode()!=200)throw new IllegalStateException("Observer HTTP "+r.statusCode());return (Map<String,Object>)AuthLabCases.object(r.body()).get("data");
    }
    Before before(HttpRequest q)throws Exception{
        if(q.method().equals("GET")||q.headerValue("X-Auth50-Case")==null)return null;
        String[] p=URI.create(q.url()).getPath().split("/");if(p.length<7||!p[5].equals("features"))return null;
        String profile=p[3],actor="anonymous";boolean found=false;
        for(var bundle:credentials.get(profile).entrySet()){
            if(bundle.getKey().equals("anonymous"))continue;boolean equal=true;
            for(var h:bundle.getValue().entrySet())if(!h.getKey().equalsIgnoreCase("X-CSRF-Token")&&!Objects.equals(q.headerValue(h.getKey()),h.getValue()))equal=false;
            if(equal){actor=bundle.getKey();found=true;break;}
        }
        boolean has=q.headers().stream().anyMatch(h->AuthComparePanel.DEFAULT_AUTH.contains(h.name().toLowerCase(Locale.ROOT)));if(has&&!found)throw new IllegalStateException("Unknown observed credentials");
        int fid=Integer.parseInt(q.headerValue("X-Auth50-Feature"));return new Before(q.headerValue("X-Auth50-Case"),actor,profile,p[4],fid,state(profile,p[4],fid));
    }
    void after(Before b,int status)throws Exception{
        if(b==null)return;var after=state(b.profile,b.mode,b.featureId);Map<String,Object> row=new LinkedHashMap<>();row.put("actor",b.actor);row.put("before",b.state);row.put("after",after);row.put("status",status);row.put("stateChanged",!Objects.equals(b.state.get("revision"),after.get("revision")));observations.computeIfAbsent(b.caseId,k->Collections.synchronizedList(new ArrayList<>())).add(row);
    }
    Map<String,Object> observation(String id,String actor){var all=observations.getOrDefault(id,List.of());synchronized(all){for(int i=all.size()-1;i>=0;i--)if(all.get(i).get("actor").equals(actor))return all.get(i);}throw new IllegalStateException("Missing independent write observation: "+id+"/"+actor);}
}
