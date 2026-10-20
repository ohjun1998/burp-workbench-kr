package kr.workbench;

import java.util.*;

/** Analyses every bounded unit, including the tail of large bundles, off the UI thread. */
final class JsBatchAnalysis {
  static JsAnalysis.Result analyze(String text,String source,String type,String document){
    if(text.length()<=JsAnalysis.SOURCE_LIMIT)return JsAnalysis.analyzeUnit(text,source,type,document,true);
    List<JsAnalysis.Endpoint> endpoints=new ArrayList<>();List<JsAnalysis.Secret> secrets=new ArrayList<>();
    Map<String,JsRequestDraft.Draft> drafts=new LinkedHashMap<>();JsMiner.Result miner=null;
    boolean partial=false,contextLost=false;int units=0,forced=0,baseLine=0;JsBatchContext context=new JsBatchContext(text);
    for(int start=0;start<text.length();){
      if(Thread.currentThread().isInterrupted()){partial=true;break;}
      Cut cut=cut(text,start);int end=cut.end();String unit=text.substring(start,end);
      contextLost|=!cut.safe();partial|=contextLost;String prelude=contextLost?"":context.prelude(start);int prefix=prelude.length(),prefixLines=(int)prelude.chars().filter(c->c=='\n').count();
      var result=JsAnalysis.analyzeUnit(prelude+unit,source,type,document,true);units++;partial|=result.truncated();
      if(result.status().contains("AST 실패"))forced++;
      for(var endpoint:result.endpoints()){
        if(endpoint.start()>=0&&endpoint.start()<prefix)continue;
        var moved=new JsAnalysis.Endpoint(endpoint.url(),endpoint.method(),endpoint.kind(),endpoint.parameters(),endpoint.headers(),endpoint.bodyKeys(),(endpoint.start()<0?endpoint.line():endpoint.line()-prefixLines+baseLine),endpoint.start()<0?endpoint.start():endpoint.start()-prefix+start,endpoint.length(),endpoint.complete()&&!contextLost,endpoint.evidence()+(contextLost?" · 분할 경계 문맥 미확정":"")+" · 분석 구간 "+start+"–"+end);
        endpoints.add(moved);var draft=result.requestDrafts().get(JsAnalysis.draftKey(endpoint));if(draft!=null)drafts.put(JsAnalysis.draftKey(moved),contextLost?JsRequestDraft.preview(moved):draft);
      }
      for(var secret:result.secrets())if(secret.start()<0||secret.start()>=prefix)secrets.add(new JsAnalysis.Secret(secret.rule(),secret.masked(),secret.length(),secret.fingerprint(),(secret.start()<0?secret.line():secret.line()-prefixLines+baseLine),secret.start()<0?secret.start():secret.start()-prefix+start,secret.sourceLength(),secret.confidence()));
      miner=miner==null?result.miner():merge(miner,result.miner());
      baseLine+=(int)unit.chars().filter(c->c=='\n').count();start=end;
    }
    if(miner==null)miner=new JsMiner.Result(List.of(),List.of(),List.of(),List.of(),List.of(),"");
    String status="분할 분석 "+units+"구간 · URL/API "+endpoints.size()+" · 시크릿 후보 "+secrets.size()+" · "+text.length()+"자 확인";
    if(forced>0){partial=true;status+=" · 문맥 미확정 구간 "+forced+"개 · 구간 간 변수는 수동 확인";}
    if(units>1){partial=true;status+=" · "+context.note();}
    if(partial)status+=" · 일부 구문/시간 제한, 미확정 결과 포함";
    return new JsAnalysis.Result(List.copyOf(endpoints),List.copyOf(secrets),status,partial,miner,Map.copyOf(drafts));
  }
  static JsMiner.Result merge(JsMiner.Result a,JsMiner.Result b){
    var recon=JsMiner.mergeRecon(a,b);List<JsMiner.MapRef> maps=new ArrayList<>(a.maps());for(var map:b.maps())if(!maps.contains(map))maps.add(map);
    List<JsMiner.Recovered> recovered=new ArrayList<>(a.recovered());JsMiner.appendRecovered(recovered,b.recovered());
    return new JsMiner.Result(recon.domains(),recon.clouds(),recon.dependencies(),List.copyOf(maps),List.copyOf(recovered),a.note());
  }
  // Prefer a top-level statement boundary. A single huge function is parsed as one unit
  // up to 4 Mi characters; larger scopes fall back to explicitly marked literal candidates.
  record Cut(int end,boolean safe){}
  static int boundary(String text,int start){return cut(text,start).end();}
  static Cut cut(String text,int start){
    int target=Math.min(text.length()-start,JsAnalysis.SOURCE_LIMIT),hard=Math.min(text.length()-start,4*1024*1024);
    var cursor=new JsSyntax.Cursor(text.substring(start));int braces=0,parens=0,squares=0;JsSyntax.Token token;
    while((token=cursor.next())!=null){
      if(Thread.currentThread().isInterrupted())return new Cut(start+hard,false);
      if(token.end()>hard)return new Cut(start+hard,false);
      if(!token.quoted())switch(token.text()){
        case "{"->braces++;case "}"->braces--;case "("->parens++;case ")"->parens--;case "["->squares++;case "]"->squares--;
      }
      if(token.end()>=target&&braces==0&&parens==0&&squares==0&&token.text().equals(";"))return new Cut(start+token.end(),true);
    }
    return new Cut(start+hard,hard==text.length()-start&&braces==0&&parens==0&&squares==0);
  }
}
