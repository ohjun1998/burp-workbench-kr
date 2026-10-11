package kr.workbench;
import java.util.*;
/** Bounded JSON comparisons. Findings remain observations requiring human confirmation. */
final class AuthJudgement {
 enum Empty{OBJECT,ARRAY}
 record Rules(Set<String> ignored,Map<String,String> denied,String ownerPath,AuthPolicy.Access policy){Rules(Set<String> ignored,Map<String,String> denied,String ownerPath){this(ignored,denied,ownerPath,AuthPolicy.Access.UNSPECIFIED);}Rules{ignored=Set.copyOf(ignored);denied=Map.copyOf(denied);}}
 record Result(String state,String reason,String changes){String note(){return state+" · "+reason;}}
 static Rules rules(String ignored,String denied,String owner){
  if(ignored.length()>2048||denied.length()>4096||owner.length()>128)throw new IllegalArgumentException("판정 규칙 크기 제한");
  Set<String> skip=new LinkedHashSet<>();for(String p:ignored.split(","))if(!p.isBlank())skip.add(p.strip());
  Map<String,String> block=new LinkedHashMap<>();for(String line:denied.split("\\R")){if(line.isBlank())continue;int i=line.indexOf('=');if(i<1)throw new IllegalArgumentException("차단 규칙은 JSON 경로=값 형식입니다.");block.put(line.substring(0,i).strip(),line.substring(i+1).strip());}
  if(skip.size()>32||block.size()>32)throw new IllegalArgumentException("규칙은 각각 최대 32개입니다.");return new Rules(skip,block,owner.strip());
 }
 static Result compare(int originalStatus,String original,int status,String body,Rules rules,String expectedOwner){return compare(originalStatus,original,status,body,rules,expectedOwner,false);}
 static Result compare(int originalStatus,String original,int status,String body,Rules rules,String expectedOwner,boolean existenceVerified){
  if(originalStatus<200||originalStatus>=300)return result("판정 보류","기준 응답 확인 필요","");
  if(status==401||status==403)return result("차단 근거 확인","HTTP "+status,"");
  if(status==404||status==410)return result(existenceVerified?"은닉형 접근 차단 후보":"은닉형 차단/미발견 후보",existenceVerified?"소유자 재조회 2xx 동일 · 대상 HTTP "+status:"기준 2xx · 대상 HTTP "+status+" · 존재/삭제 여부 재검증 필요","");
  if(status>=300&&status<400)return result("판정 보류","리다이렉트 · 로그인 여부 확인","");
  if(status<200||status>=300)return result("판정 보류","HTTP "+status,"");
  if(original.isBlank()||body.isBlank())return result("판정 보류","빈 성공 응답 · 데이터/실행 결과 확인","");
  if(rules.policy==AuthPolicy.Access.PUBLIC){Map<String,Object> publicBaseline=json(original),publicJson=json(body);if(publicBaseline!=null&&denied(publicBaseline,rules))return result("판정 보류","기준 응답에 차단 규칙 일치","");if(publicJson!=null&&denied(publicJson,rules))return result("차단 근거 확인","설정한 JSON 차단 규칙 일치","");return result("공개/공유 허용","설정한 경로·메서드 정책 · 성공 응답 관측","");}
  Map<String,Object> a=json(original),b=json(body);
  if(a==null||b==null)return result(original.equals(body)?"우회 의심":"판정 보류",original.equals(body)?"동일 성공 응답 · 수동 확인":"비 JSON/구조 제한 · 본문 수동 확인","");
  if(denied(a,rules))return result("판정 보류","기준 응답에 차단 규칙 일치","");
  String changes=changes(a,b,rules.ignored);
  if(denied(b,rules))return result("차단 근거 확인","설정한 JSON 차단 규칙 일치",changes);

  if(!rules.ownerPath.isBlank()&&!expectedOwner.isBlank()){
   Object before=value(a,rules.ownerPath),after=value(b,rules.ownerPath);
   if(before==null||after==null)return result("판정 보류","소유 JSON 경로 누락 · "+rules.ownerPath+" · 원본/대상 필드 확인",changes);
   if(Objects.equals(before,after)&&!text(after).equals(expectedOwner))return rules.policy==AuthPolicy.Access.OWNER_ONLY?result("타인 객체 접근 의심","소유자 전용 정책 · 소유 필드가 대상 계정 소유값과 다름",changes):result("소유값 불일치 · 정책 확인","공개/공유/소유자 전용 정책 미설정 · 인가 위반 미확정",changes);
  }
  Map<String,Object> aa=without(a,rules.ignored),bb=without(b,rules.ignored);
  if(aa.isEmpty()||bb.isEmpty())return result("판정 보류","비교 가능한 JSON 필드 없음",changes);
  return aa.equals(bb)?result("우회 의심","비교 대상 JSON 필드 동일 · 실제 접근 확인",changes):result("판정 보류","JSON 필드 차이 · 데이터/실행 결과 확인",changes);
 }
 private static Result result(String s,String r,String c){return new Result(s,r,c);}
 static Map<String,Object> json(String body){try{Object doc=MinerJson.parse(body,128*1024,20000);if(!(doc instanceof Map<?,?>)&&!(doc instanceof List<?>))return null;Map<String,Object> out=new TreeMap<>();flatten(doc,"$",out);return out;}catch(Exception e){return null;}}
 private static void flatten(Object x,String path,Map<String,Object> out){if(out.size()>=8000)throw new IllegalArgumentException("필드 제한");if(x instanceof Map<?,?> m){if(m.isEmpty())out.put(path,Empty.OBJECT);else for(var e:m.entrySet())flatten(e.getValue(),path+(e.getKey().toString().matches("[A-Za-z_][A-Za-z0-9_]*")?"."+e.getKey():"["+MetadataExport.json(e.getKey())+"]"),out);}else if(x instanceof List<?> l){if(l.isEmpty())out.put(path,Empty.ARRAY);else for(int i=0;i<l.size();i++)flatten(l.get(i),path+"["+i+"]",out);}else out.put(path,x);}
 private static boolean match(String path,String rule){return rule.startsWith("$")?path.equals(rule):path.equals("$."+rule)||path.endsWith("."+rule);}
 static Object value(Map<String,Object> map,String path){String key=path.startsWith("$")?path:"$."+path;return map.get(key);}
 private static boolean denied(Map<String,Object> map,Rules r){for(var rule:r.denied.entrySet())for(var e:map.entrySet())if(match(e.getKey(),rule.getKey())&&text(e.getValue()).equals(rule.getValue()))return true;return false;}
 private static String text(Object v){if(v instanceof Number n){var d=new java.math.BigDecimal(n.toString()).stripTrailingZeros();return Math.abs((long)d.scale())<128&&d.precision()-(long)d.scale()<128?d.toPlainString():d.toString();}return String.valueOf(v);}
 static Map<String,Object> without(Map<String,Object> map,Set<String> ignored){Map<String,Object> out=new TreeMap<>();for(var e:map.entrySet())if(ignored.stream().noneMatch(r->match(e.getKey(),r)))out.put(e.getKey(),e.getValue());return out;}
 static String changes(Map<String,Object> a,Map<String,Object> b,Set<String> ignored){Set<String> keys=new TreeSet<>(a.keySet());keys.addAll(b.keySet());StringBuilder out=new StringBuilder();int count=0;for(String k:keys){if(ignored.stream().anyMatch(r->match(k,r)))continue;if(a.containsKey(k)!=b.containsKey(k)||!Objects.equals(a.get(k),b.get(k))){if(count++>=100){out.append("[차이 100개 제한]\n");break;}out.append(k).append(" : ").append(display(a,k)).append(" → ").append(display(b,k)).append('\n');}}return out.length()==0?"비교 대상 필드 차이 없음":out.toString();}
 private static String display(Map<String,Object> m,String k){if(!m.containsKey(k))return "[없음]";String s=MetadataExport.json(m.get(k));return s.length()>160?s.substring(0,160)+"…":s;}
}
