package kr.workbench;

import java.util.*;

/** Analyses every bounded unit, including the tail of large bundles, off the UI thread. */
final class JsBatchAnalysis {
  static JsAnalysis.Result analyze(String text,String source,String type,String document){
    if(text.length()<=JsAnalysis.SOURCE_LIMIT)return JsAnalysis.analyzeUnit(text,source,type,document,true);
    List<JsAnalysis.Endpoint> endpoints=new ArrayList<>();List<JsAnalysis.Secret> secrets=new ArrayList<>();
    Map<String,JsRequestDraft.Draft> drafts=new LinkedHashMap<>();JsMiner.Result miner=null;
    boolean partial=false;int units=0,forced=0,baseLine=0;
    for(int start=0;start<text.length();){
      if(Thread.currentThread().isInterrupted()){partial=true;break;}
      int end=boundary(text,start);String unit=text.substring(start,end);
      var result=JsAnalysis.analyzeUnit(unit,source,type,document,true);units++;partial|=result.truncated();
      if(result.status().contains("AST 실패"))forced++;
      for(var endpoint:result.endpoints()){
        var moved=new JsAnalysis.Endpoint(endpoint.url(),endpoint.method(),endpoint.kind(),endpoint.parameters(),endpoint.headers(),endpoint.bodyKeys(),(endpoint.start()<0?endpoint.line():endpoint.line()+baseLine),endpoint.start()<0?endpoint.start():endpoint.start()+start,endpoint.length(),endpoint.complete(),endpoint.evidence()+" · 분석 구간 "+start+"–"+end);
        endpoints.add(moved);var draft=result.requestDrafts().get(JsAnalysis.draftKey(endpoint));if(draft!=null)drafts.put(JsAnalysis.draftKey(moved),draft);
      }
      for(var secret:result.secrets())secrets.add(new JsAnalysis.Secret(secret.rule(),secret.masked(),secret.length(),secret.fingerprint(),(secret.start()<0?secret.line():secret.line()+baseLine),secret.start()<0?secret.start():secret.start()+start,secret.sourceLength(),secret.confidence()));
      miner=miner==null?result.miner():merge(miner,result.miner());
      baseLine+=(int)unit.chars().filter(c->c=='\n').count();start=end;
    }
    if(miner==null)miner=new JsMiner.Result(List.of(),List.of(),List.of(),List.of(),List.of(),"");
    String status="분할 분석 "+units+"구간 · URL/API "+endpoints.size()+" · 시크릿 후보 "+secrets.size()+" · "+text.length()+"자 확인";
    if(forced>0){partial=true;status+=" · 문맥 미확정 구간 "+forced+"개 · 구간 간 변수는 수동 확인";}
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
  static int boundary(String text,int start){
    int target=Math.min(text.length(),start+JsAnalysis.SOURCE_LIMIT),hard=Math.min(text.length(),start+4*1024*1024);
    int braces=0,parens=0,squares=0;char quote=0;boolean line=false,block=false,escape=false;
    for(int i=start;i<hard;i++){char c=text.charAt(i),next=i+1<text.length()?text.charAt(i+1):0;
      if(line){if(c=='\n')line=false;continue;}if(block){if(c=='*'&&next=='/'){block=false;i++;}continue;}
      if(quote!=0){if(escape)escape=false;else if(c=='\\')escape=true;else if(c==quote)quote=0;continue;}
      if(c=='/'&&next=='/'){line=true;i++;continue;}if(c=='/'&&next=='*'){block=true;i++;continue;}
      if(c=='\''||c=='"'||c=='`'){quote=c;continue;}
      if(c=='{')braces++;else if(c=='}')braces=Math.max(0,braces-1);else if(c=='(')parens++;else if(c==')')parens=Math.max(0,parens-1);else if(c=='[')squares++;else if(c==']')squares=Math.max(0,squares-1);
      if(i+1>=target&&braces==0&&parens==0&&squares==0&&(c==';'||c=='\n'))return i+1;
    }
    return hard;
  }
}
