package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.RedirectionMode;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.TableRowSorter;

/** Same production comparison controller and real HTTP in native and adapter transports. */
public final class AuthLabCases {
    static final List<String> PROFILES=List.of("session","token","jwt","session-token","session-jwt","token-jwt","all");
    static final List<String> ACTORS=List.of("admin","alice","bob","anonymous");
    final MontoyaApi api;final AuthComparePanel panel;final String base;final Path out;
    final List<Map<String,Object>> comparisons=new ArrayList<>(),identityChecks=new ArrayList<>();
    AuthLabCases(MontoyaApi api,AuthComparePanel panel,String base,Path out){this.api=api;this.panel=panel;this.base=base;this.out=out;}
    static void edt(Runnable action)throws Exception{SwingUtilities.invokeAndWait(action);}
    static void require(boolean condition,String label){if(!condition)throw new AssertionError(label);}
    @SuppressWarnings("unchecked") static Map<String,Object> object(String json){return (Map<String,Object>)MinerJson.parse(json);}
    static String hash(String body)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));}
    HttpRequest request(String path,String method,Map<String,String> headers,Map<String,String> fields){
        HttpRequest q=HttpRequest.httpRequestFromUrl(base+path).withMethod(method).withHeader("Accept","application/json");
        for(var h:headers.entrySet())q=q.withHeader(h.getKey(),h.getValue());
        if(fields!=null){String body=String.join("&",fields.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).toList());q=q.withHeader("Content-Type","application/x-www-form-urlencoded;charset=UTF-8").withBody(body);}
        return q;
    }
    HttpRequestResponse send(HttpRequest q){return api.http().sendRequest(q,RequestOptions.requestOptions().withResponseTimeout(10000).withRedirectionMode(RedirectionMode.NEVER));}
    Map<String,String> login(String profile,String username,String lifetime){
        var response=send(request("/api/auth-lab/login","POST",Map.of(),Map.of("profile",profile,"username",username,"password","LabOnly!2026","lifetime",lifetime))).response();
        require(response!=null&&response.statusCode()==200,"login failed: "+profile+"/"+username);
        @SuppressWarnings("unchecked") Map<String,String> values=(Map<String,String>)object(response.bodyToString()).get("headers");return Map.copyOf(values);
    }
    void idle()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(System.nanoTime()<until){edt(()->{});if(panel.active.get()==0){edt(()->{});return;}Thread.sleep(15);}throw new AssertionError("comparison timeout");}
    void assign(String profile,List<Map<String,String>> credentials)throws Exception{
        for(int i=0;i<3;i++){
            int slot=i;HttpRequest q=request("/api/auth-lab/"+profile+"/me","GET",credentials.get(i),null);var response=send(q).response();
            require(response.statusCode()==200,"identity HTTP failed: "+profile+"/"+ACTORS.get(i));
            var config=new CookieSessionProbe.Config(base+"/api/auth-lab/"+profile+"/me","$.user.id",String.valueOf(i==0?3:i),List.of(new CookieSessionProbe.Claim("역할","$.user.role",i==0?"ADMIN":"USER")));
            Map<String,String> lower=new HashMap<>();credentials.get(i).forEach((k,v)->lower.put(k.toLowerCase(Locale.ROOT),v));
            CookieSessionProbe.verify(api,config,lower,AuthComparePanel.DEFAULT_AUTH,base,()->false);
            identityChecks.add(Map.of("profile",profile,"actor",ACTORS.get(i),"status",200,"identityMatched",true,"anonymousRejected",true));
            edt(()->{
                var observation=panel.prepareObservation(q,response);require(observation!=null,"credential observation missing");panel.applyObservation(observation);
                var candidate=panel.credentials.get(observation.key());require(candidate!=null,"credential candidate missing");panel.assign(slot,candidate);panel.profileNames[slot].setText(ACTORS.get(slot));
            });
        }
        edt(()->panel.anonymous(3));
    }
    void compare(String profile,String route,int baseline,int[] statuses,String[] policy,boolean suspected)throws Exception{
        String path="/api/auth-lab/"+profile+"/"+route;
        Map<String,String> headers=new LinkedHashMap<>();panel.slots[baseline].headers().forEach(headers::put);
        var original=request(path,"GET",headers,null);var captured=send(original).response();require(captured.statusCode()==200,"captured baseline not successful");
        int prior=panel.runs.size();
        edt(()->{panel.matrixRules.setText("GET "+path+" "+String.join(" ",policy));panel.enqueue(original,captured,panel.context());});idle();
        require(panel.runs.size()==prior+1,"comparison history count");var run=panel.runs.get(panel.runs.size()-1);var summary=AuthRunSummary.of(run);
        require(run.baselineState==AuthComparePanel.Run.Baseline.VALID,"fresh baseline invalid for "+route);
        require((summary.verdict()==AuthVerdict.SUSPECT)==suspected,"aggregate mismatch for "+profile+"/"+route+": "+summary.label()+" "+summary.reason());
        require(suspected||summary.verdict()==AuthVerdict.BLOCKED,"unexpected inconclusive normal result");
        List<Map<String,Object>> slots=new ArrayList<>();
        for(int i=0;i<4;i++){
            var packet=run.packets[i];require(packet.response!=null,"missing response "+profile+"/"+route+"/"+i);
            require(packet.response.statusCode()==statuses[i],"HTTP mismatch "+profile+"/"+route+"/"+ACTORS.get(i));
            if(i!=baseline&&policy[i].equals("DENY"))require((packet.finding().verdict()==AuthVerdict.SUSPECT)==(statuses[i]==200),"per-account policy mismatch");
            Map<String,Object> row=new LinkedHashMap<>();row.put("actor",ACTORS.get(i));row.put("reference",i==baseline);row.put("expectedPolicy",policy[i]);row.put("expectedStatus",statuses[i]);row.put("status",(int)packet.response.statusCode());row.put("finding",packet.finding().kind().name());row.put("verdict",packet.finding().verdict().name());row.put("reason",packet.finding().note());row.put("responseSha256",hash(packet.response.bodyToString()));row.put("responseExcerpt",packet.response.bodyToString().substring(0,Math.min(1200,packet.response.bodyToString().length())));row.put("requestHeaders",packet.request==null?List.of():packet.request.headers().stream().map(h->h.name()+": "+(AuthComparePanel.DEFAULT_AUTH.contains(h.name().toLowerCase(Locale.ROOT))||AuthComparePanel.CSRF.contains(h.name().toLowerCase(Locale.ROOT))?"[REDACTED]":h.value())).toList());slots.add(row);
        }
        comparisons.add(Map.of("id",String.format("EXT-%03d",comparisons.size()+1),"profile",profile,"path",path,"method","GET","baselineActor",ACTORS.get(baseline),"expectedSuspect",suspected,"aggregate",summary.label(),"verdict",summary.verdict().name(),"accounts",slots,"passed",true));
        System.out.println("AUTH_COMPARISON_PASS "+profile+" "+route+" "+summary.label());
    }
    void expiredBaseline()throws Exception{
        var credentials=login("jwt","alice","short");var original=request("/api/auth-lab/jwt/fixed/member","GET",credentials,null);var captured=send(original).response();require(captured.statusCode()==200,"short session baseline captured");Thread.sleep(2300);
        edt(()->{panel.matrixRules.setText("GET /api/auth-lab/jwt/fixed/member ALLOW ALLOW ALLOW DENY");panel.enqueue(original,captured,panel.context());});idle();var run=panel.runs.get(panel.runs.size()-1);
        require(run.baselineState==AuthComparePanel.Run.Baseline.INVALID&&AuthRunSummary.of(run).label().equals("세션 확인 필요"),"expired baseline must not become a green denial");
        require(Arrays.stream(run.packets).allMatch(p->p.response==null),"invalid baseline must prevent every account replay");
        identityChecks.add(Map.of("profile","jwt","actor","expired-alice","status",401,"baselineInvalid",true,"accountReplays",0,"aggregate",AuthRunSummary.of(run).label()));
    }
    void screenshot()throws Exception{
        edt(()->{try{
            @SuppressWarnings("unchecked") var sorter=(TableRowSorter<?>)panel.history.getRowSorter();sorter.setRowFilter(RowFilter.regexFilter("/all/",1));
            JPanel root=new JPanel(new BorderLayout(8,8));root.setBorder(BorderFactory.createEmptyBorder(14,14,14,14));JLabel title=new JLabel("계정 권한 비교 0.43.0 · 세션 + 토큰 + JWT · 실제 비교 기록");root.add(title,BorderLayout.NORTH);JScrollPane scroll=new JScrollPane(panel.history);scroll.setColumnHeaderView(panel.history.getTableHeader());root.add(scroll);WorkbenchUi.style(root);root.setSize(1440,370);layout(root);BufferedImage image=new BufferedImage(1440,370,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();root.printAll(g);g.dispose();ImageIO.write(image,"png",out.resolve("extension-history.png").toFile());
        }catch(Exception error){throw new RuntimeException(error);}});
    }
    static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container sub)layout(sub);}
    public Map<String,Object> run()throws Exception{
        Files.createDirectories(out);WorkbenchScope.install(api).add(base+"/",true);
        for(String profile:PROFILES){
            List<Map<String,String>> credentials=new ArrayList<>();for(String actor:ACTORS.subList(0,3))credentials.add(login(profile,actor,"normal"));assign(profile,credentials);
            compare(profile,"fixed/member",0,new int[]{200,200,200,401},new String[]{"ALLOW","ALLOW","ALLOW","DENY"},false);
            compare(profile,"fixed/admin/users",0,new int[]{200,403,403,401},new String[]{"ALLOW","DENY","DENY","DENY"},false);
            compare(profile,"vuln/admin/users",0,new int[]{200,200,200,401},new String[]{"ALLOW","DENY","DENY","DENY"},true);
            compare(profile,"fixed/requests/901",1,new int[]{200,200,404,401},new String[]{"ALLOW","ALLOW","DENY","DENY"},false);
            compare(profile,"fixed/requests/902",2,new int[]{200,404,200,401},new String[]{"ALLOW","DENY","ALLOW","DENY"},false);
            compare(profile,"vuln/requests/901",1,new int[]{200,200,200,401},new String[]{"ALLOW","ALLOW","DENY","DENY"},true);
        }
        expiredBaseline();screenshot();return Map.of("result","passed","extensionVersion",BuildInfo.VERSION,"completedAt",Instant.now().toString(),"comparisonRuns",comparisons.size(),"accountCells",comparisons.size()*4,"referenceCells",comparisons.size(),"comparedAccountCells",comparisons.size()*3,"comparisons",comparisons,"identityChecks",identityChecks);
    }
}
