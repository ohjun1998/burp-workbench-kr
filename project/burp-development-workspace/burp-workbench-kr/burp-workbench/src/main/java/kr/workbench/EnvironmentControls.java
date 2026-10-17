package kr.workbench;
import java.util.*;
/** Missing-path controls must be stable before being used to suppress a finding. */
final class EnvironmentControls {
 record Sample(int status,String type,String signature){}
 record Reference(Sample sample,boolean stable,String note){}
 static Sample sample(int status,String headers,String body,String url,Set<String> ignored){
  if(body.length()>65536)return new Sample(status,""," ".strip());String signature;Map<String,Object> json=AuthJudgement.json(body);
  if(json==null){String text=EnvironmentResponse.signature(body,url);signature=text.isEmpty()?"":"TEXT:"+text;}
  else{Map<String,String> typed=new TreeMap<>();for(var e:AuthJudgement.without(json,ignored).entrySet()){Object v=e.getValue();String type=v==null?"null":v instanceof Number?"number":v instanceof Boolean?"boolean":v instanceof AuthJudgement.Empty?"container":"string";String value=v instanceof String s?EnvironmentResponse.signature(s,url):MetadataExport.json(v);typed.put(e.getKey(),type+":"+value);}signature=typed.isEmpty()?"":"JSON:"+MetadataExport.json(typed);}
  return new Sample(status,EnvironmentResponse.header(headers,"Content-Type").split(";",2)[0].toLowerCase(Locale.ROOT),signature);
 }
 static boolean similar(Sample a,Sample b){return a.status==b.status&&a.type.equals(b.type)&&EnvironmentContent.similar(a.signature,b.signature);}
 static Reference reference(Sample a,Sample b,boolean repeat,String issue){if(a==null)return new Reference(null,false,"기준 비교 불가 · "+issue);if(repeat&&(b==null||(!a.equals(b)&&!similar(a,b))))return new Reference(a,false,"기준 응답 불안정/재검증 실패 · 수동 검토");if(a.signature.isEmpty())return new Reference(a,false,"기준 비교 필드 없음/본문 한도");return new Reference(a,true,repeat?(a.equals(b)?"기준 2회 동일":"기준 2회 유사 · 수동 검토"):"기준 1회");}
 static String state(int status,String headers,String body,String url,Reference reference,Set<String> ignored){String result=EnvironmentResponse.state(status,headers,body,url,null);if(reference==null)return result;Sample sample=sample(status,headers,body,url,ignored);if(status>=200&&status<300&&reference.stable&&sample.equals(reference.sample))result="HTTP "+status+" · 없는 경로와 동일";else if(status>=200&&status<300&&reference.stable&&similar(sample,reference.sample))result="HTTP "+status+" · 없는 경로 기준과 유사 · 공통 화면 의심";return result+" · "+reference.note;}
}
