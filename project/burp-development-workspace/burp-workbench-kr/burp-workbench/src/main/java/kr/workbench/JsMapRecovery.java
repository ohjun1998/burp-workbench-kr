package kr.workbench;

import java.util.*;

final class JsMapRecovery {
  static JsAnalysis.Result merge(JsAnalysis.Result original,JsMiner.Result miner,String source,String page){
    List<JsAnalysis.Endpoint> endpoints=new ArrayList<>(original.endpoints());List<JsAnalysis.Secret> secrets=new ArrayList<>(original.secrets());
    Map<String,JsRequestDraft.Draft> drafts=new LinkedHashMap<>(original.requestDrafts());boolean partial=original.truncated();
    long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
    for(var file:miner.recovered()){
      if(Thread.currentThread().isInterrupted()||System.nanoTime()>end){partial=true;break;}
      var child=JsAnalysis.analyzeContext(file.content(),source,"application/javascript",page);
      miner=JsMiner.mergeRecon(miner,child.miner());partial|=child.truncated();
      for(var item:child.endpoints()){
        var mapped=new JsAnalysis.Endpoint(item.url(),item.method(),"source map: "+file.name()+(item.kind().contains("GraphQL")?" · "+item.kind().substring(item.kind().indexOf("GraphQL")):""),item.parameters(),item.headers(),item.bodyKeys(),item.line(),-1-Math.max(0,item.start()),item.length(),item.complete(),item.evidence()+" · 원본 열 "+JsFindingGroups.column(file.content(),item.start()));
        boolean exists=endpoints.stream().anyMatch(e->e.url().equals(mapped.url())&&e.method().equals(mapped.method())&&e.kind().equals(mapped.kind())&&e.start()==mapped.start());
        if(!exists)endpoints.add(mapped);var draft=child.requestDrafts().get(JsAnalysis.draftKey(item));if(draft!=null)drafts.put(JsAnalysis.draftKey(mapped),draft);
      }
      for(var secret:child.secrets())if(secrets.stream().noneMatch(s->s.fingerprint().equals(secret.fingerprint())))secrets.add(new JsAnalysis.Secret(secret.rule(),secret.masked(),secret.length(),secret.fingerprint(),secret.line(),-1-Math.max(0,secret.start()),secret.sourceLength(),"source map: "+file.name()+" · "+secret.confidence()));
    }
    return new JsAnalysis.Result(List.copyOf(endpoints),List.copyOf(secrets),original.status()+" · 복원 원본 연결"+(partial?" · 일부 분석":""),partial,miner,Map.copyOf(drafts));
  }
}
