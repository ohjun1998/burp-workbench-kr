package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/** Paired evaluation, with an independent CSV policy and raw transport read-back oracle. */
final class Auth50Cases {
    static final List<String> ACTORS=List.of("admin","alice","bob","anonymous");
    record Feature(int id,String group,String name,String method,String path,int baseline,List<String> policy){}
    final MontoyaApi api;final AuthComparePanel first;final String base;final Path out,policyFile;final Auth50Observer observer;
    final List<Map<String,Object>> comparisons=Collections.synchronizedList(new ArrayList<>()),identityChecks=Collections.synchronizedList(new ArrayList<>());
    final List<AuthComparePanel> panels=new ArrayList<>();final AtomicInteger complete=new AtomicInteger();
    Auth50Cases(MontoyaApi api,AuthComparePanel panel,String base,Path out,Path policyFile,Auth50Observer observer){this.api=api;this.first=panel;this.base=base;this.out=out;this.policyFile=policyFile;this.observer=observer;}
    static void edt(Runnable r)throws Exception{SwingUtilities.invokeAndWait(r);}
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    List<Feature> features()throws Exception{
        List<Feature> result=new ArrayList<>();for(String line:Files.readAllLines(policyFile,StandardCharsets.UTF_8).subList(1,51)){String[] v=line.strip().split(",");result.add(new Feature(Integer.parseInt(v[0]),v[1],v[2],v[3],v[4],ACTORS.indexOf(v[5]),List.of(v[6],v[7],v[8],v[9])));}if(result.size()!=50)throw new IllegalStateException("Expected fifty independent features");return result;
    }
    void setting(AuthComparePanel panel,String setting,Feature f,String path,String profile,String mode)throws Exception{
        edt(()->{
            boolean configured=setting.equals("configured");panel.writes.setSelected(true);
            panel.matrixRules.setText(configured?f.method+" "+path+" "+String.join(" ",f.policy):"");
            panel.policyRules.setText("");panel.accessPolicy.setSelectedIndex(0);
            panel.ownerPath.setText("");panel.importantFields.setText("");panel.ignoredFields.setText(configured?"$.data.revision,$.data.eventCount,$.data.lastActor":"");
            panel.denialRules.setText("error=FORBIDDEN\nerror=UNAUTHORIZED"+(configured?"\nstatus=DENIED":""));
            panel.responseDenialRules.setText(configured?"BODY: Denied by access policy":"");
            panel.effectSettings.enabled.setSelected(configured&&!f.method.equals("GET"));
            if(configured&&!f.method.equals("GET")){panel.effectSettings.method.setSelectedItem(f.method);panel.effectSettings.path.setText(path);panel.effectSettings.url.setText(base+"/api/auth-lab/"+profile+"/"+mode+"/bench-state/"+f.id);panel.effectSettings.jsonPath.setText("$.data.revision");panel.effectSettings.mode.setSelectedIndex(1);panel.effectSettings.attempts.setValue(1);}
        });
    }
    void profile(String profile,AuthComparePanel panel,List<Feature> features)throws Exception{
        var helper=new AuthLabCases(api,panel,base,out);List<Map<String,String>> credentials=new ArrayList<>();for(String actor:ACTORS.subList(0,3))credentials.add(helper.login(profile,actor,"normal"));helper.assign(profile,credentials);observer.register(profile,credentials);identityChecks.addAll(helper.identityChecks);
        for(String setting:List.of("unconfigured","configured"))for(Feature f:features)for(String mode:List.of("fixed","vuln")){
            String path="/api/auth-lab/"+profile+"/"+mode+"/features/"+f.path,id=profile+"-"+setting+"-"+mode+"-"+f.id;
            setting(panel,setting,f,path,profile,mode);
            Map<String,String> headers=new LinkedHashMap<>(credentials.get(f.baseline));headers.put("X-Auth50-Case",id);headers.put("X-Auth50-Feature",String.valueOf(f.id));
            var q=helper.request(path,f.method,headers,null);var captured=helper.send(q).response();if(captured==null||captured.statusCode()<200||captured.statusCode()>=300)throw new IllegalStateException("Captured authorized baseline failed "+id);
            edt(()->panel.enqueue(q,captured,panel.context()));helper.idle();AuthComparePanel.Run run=panel.runs.get(panel.runs.size()-1);
            if(run.baselineState==AuthComparePanel.Run.Baseline.INVALID)throw new IllegalStateException("Invalid baseline "+id+": "+run.baselineNote);
            List<Map<String,Object>> accounts=new ArrayList<>();boolean anyViolation=false;
            for(int i=0;i<4;i++){
                var packet=run.packets[i];if(packet.response==null)throw new IllegalStateException("Missing actual response "+id+"/"+ACTORS.get(i)+": "+packet.note);
                byte[] body=packet.response.body().getBytes();boolean write=!f.method.equals("GET"),access;
                Map<String,Object> evidence=write?observer.observation(id,ACTORS.get(i)):Map.of();
                access=write?Boolean.TRUE.equals(evidence.get("stateChanged")):new String(body,StandardCharsets.UTF_8).contains("AUTH50_PRIVATE_"+f.id)||new String(body,StandardCharsets.UTF_8).contains("AUTH50_PUBLIC_"+f.id);
                boolean allowed=f.policy.get(i).equals("ALLOW"),violation=!allowed&&access,reference=i==f.baseline;anyViolation|=violation&&!reference;
                boolean alert=packet.finding().verdict()==AuthVerdict.SUSPECT;
                Map<String,Object> row=new LinkedHashMap<>();row.put("actor",ACTORS.get(i));row.put("reference",reference);row.put("expectedPolicy",f.policy.get(i));row.put("status",(int)packet.response.statusCode());row.put("observedAccess",access);row.put("groundTruthViolation",violation);row.put("alert",alert);row.put("classification",reference?"REFERENCE":violation?(alert?"TP":"FN"):(alert?"FP":"TN"));row.put("finding",packet.finding().kind().name());row.put("verdict",packet.finding().verdict().name());row.put("reason",packet.finding().note());row.put("responseSha256",hash(body));row.put("responseExcerpt",new String(body,0,Math.min(body.length,1500),StandardCharsets.UTF_8));row.put("independentWriteEvidence",evidence);row.put("effectOutcome",packet.effect==null?"NOT_CONFIGURED":packet.effect.outcome().name());row.put("requestHeaders",packet.request.headers().stream().map(h->h.name()+": "+(AuthComparePanel.DEFAULT_AUTH.contains(h.name().toLowerCase(Locale.ROOT))||AuthComparePanel.CSRF.contains(h.name().toLowerCase(Locale.ROOT))?"[REDACTED]":h.value())).toList());accounts.add(row);
            }
            var summary=AuthRunSummary.of(run);boolean alert=summary.verdict()==AuthVerdict.SUSPECT;
            Map<String,Object> record=new LinkedHashMap<>();record.put("id",id);record.put("profile",profile);record.put("setting",setting);record.put("mode",mode);record.put("featureId",f.id);record.put("featureName",f.name);record.put("group",f.group);record.put("method",f.method);record.put("path",path);record.put("baselineActor",ACTORS.get(f.baseline));record.put("aggregate",summary.label());record.put("aggregateVerdict",summary.verdict().name());record.put("groundTruthViolation",anyViolation);record.put("classification",anyViolation?(alert?"TP":"FN"):(alert?"FP":"TN"));record.put("accounts",accounts);comparisons.add(record);
            int count=complete.incrementAndGet();if(count%25==0){synchronized(this){Files.writeString(out.resolve("progress.json"),MetadataExport.json(Map.of("completed",count,"planned",1400,"lastCase",id))+"\n");}System.out.println("AUTH50_PROGRESS "+count+" / 1400 "+id);}
        }
    }
    static Map<String,Object> metrics(List<Map<String,Object>> records,boolean account){
        Map<String,Integer> counts=new LinkedHashMap<>();for(String k:List.of("TP","FP","TN","FN"))counts.put(k,0);int review=0;
        for(var r:records){List<Map<String,Object>> samples;if(account){@SuppressWarnings("unchecked") var slots=(List<Map<String,Object>>)r.get("accounts");samples=slots.stream().filter(s->!Boolean.TRUE.equals(s.get("reference"))).toList();}else samples=List.of(r);
            for(var s:samples){String key=(String)s.get("classification");counts.put(key,counts.get(key)+1);if("REVIEW".equals(s.get(account?"verdict":"aggregateVerdict")))review++;}}
        int tp=counts.get("TP"),fp=counts.get("FP"),fn=counts.get("FN"),tn=counts.get("TN");Map<String,Object> result=new LinkedHashMap<>(counts);result.put("precision",tp+fp==0?null:(double)tp/(tp+fp));result.put("recall",tp+fn==0?null:(double)tp/(tp+fn));result.put("falsePositiveRate",fp+tn==0?null:(double)fp/(fp+tn));result.put("reviewCount",review);result.put("samples",tp+fp+fn+tn);return result;
    }
    Map<String,Object> run()throws Exception{
        Files.createDirectories(out);var features=features();WorkbenchScope.install(api).add(base+"/",true);
        edt(()->{panels.add(first);for(int i=1;i<7;i++)panels.add(new AuthComparePanel(api,List::of));});
        var pool=Executors.newFixedThreadPool(7);List<Future<?>> jobs=new ArrayList<>();
        try{for(int i=0;i<7;i++){String profile=AuthLabCases.PROFILES.get(i);AuthComparePanel panel=panels.get(i);jobs.add(pool.submit(()->{try{profile(profile,panel,features);}catch(Exception error){throw new CompletionException(error);}}));}for(var job:jobs)job.get();}
        finally{pool.shutdownNow();for(int i=1;i<panels.size();i++){AuthComparePanel panel=panels.get(i);edt(panel::close);}}
        comparisons.sort(Comparator.comparing(m->m.get("id").toString()));Map<String,Object> metrics=new LinkedHashMap<>();for(String setting:List.of("unconfigured","configured")){var filtered=comparisons.stream().filter(r->r.get("setting").equals(setting)).toList();metrics.put(setting,Map.of("run",metrics(filtered,false),"account",metrics(filtered,true)));}
        Map<String,Object> result=new LinkedHashMap<>();result.put("result","completed");result.put("extensionVersion",BuildInfo.VERSION);result.put("uniqueFunctions",50);result.put("comparisonRuns",comparisons.size());result.put("accountCells",comparisons.size()*4);result.put("referenceCells",comparisons.size());result.put("comparedAccountCells",comparisons.size()*3);result.put("identityChecks",identityChecks);result.put("comparisons",comparisons);result.put("metrics",metrics);result.put("independentObserverRequests",observer.requests.get());result.put("writeGroundTruth","Raw HTTP read-back around actual transport; state revision from fresh database queries; classified with independent CSV, never server policy labels.");result.put("parallelism","Seven isolated production controller instances, one per authentication profile; shipped worker delays and production methods unchanged.");return result;
    }
}
