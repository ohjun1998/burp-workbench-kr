package kr.workbench;
import java.util.*;
/** Bounded JSON comparisons. Findings remain observations requiring human confirmation. */
final class AuthJudgement {
 enum Empty{OBJECT,ARRAY}
 record Denial(String path,String value){}
 record Rules(Set<String> ignored,List<Denial> denied,String ownerPath,AuthPolicy.Access policy,Set<String> important,boolean unorderedArrays){
  Rules(Set<String> ignored,Map<String,String> denied,String ownerPath,AuthPolicy.Access policy){this(ignored,denied.entrySet().stream().map(e->new Denial(e.getKey(),e.getValue())).toList(),ownerPath,policy,Set.of(),false);}
  Rules(Set<String> ignored,List<Denial> denied,String ownerPath){this(ignored,denied,ownerPath,AuthPolicy.Access.UNSPECIFIED,Set.of(),false);}
  Rules(Set<String> ignored,List<Denial> denied,String ownerPath,AuthPolicy.Access policy){this(ignored,denied,ownerPath,policy,Set.of(),false);}
  Rules{ignored=Set.copyOf(ignored);denied=List.copyOf(denied);important=Set.copyOf(important);if(important.size()>32)throw new IllegalArgumentException("중요 필드 최대 32개");for(String field:important)if(ignored.stream().anyMatch(skip->match(field,skip)||match(skip,field)))throw new IllegalArgumentException("중요 필드를 비교 제외할 수 없습니다.");if(!ownerPath.isBlank()&&ignored.stream().anyMatch(skip->match(ownerPath,skip)))throw new IllegalArgumentException("소유 필드를 비교 제외할 수 없습니다.");}
  Rules withPolicy(AuthPolicy.Access policy){return new Rules(ignored,denied,ownerPath,policy,important,unorderedArrays);}
 }
 record Result(AuthFinding.Kind kind,String reason,String changes){String state(){return kind.label;}String note(){return finding().note();}AuthFinding finding(){return new AuthFinding(kind,reason,changes);}}
 static Rules rules(String ignored,String denied,String owner){
  if(ignored.length()>2048||denied.length()>4096||owner.length()>128)throw new IllegalArgumentException("판정 규칙 크기 제한");
  Set<String> skip=new LinkedHashSet<>();for(String p:ignored.split(","))if(!p.isBlank())skip.add(p.strip());
  List<Denial> block=new ArrayList<>();for(String line:denied.split("\\R")){if(line.isBlank())continue;int i=line.indexOf('=');if(i<1)throw new IllegalArgumentException("차단 규칙은 JSON 경로=값 형식입니다.");block.add(new Denial(line.substring(0,i).strip(),line.substring(i+1).strip()));}
  if(skip.size()>32||block.size()>32)throw new IllegalArgumentException("규칙은 각각 최대 32개입니다.");return new Rules(skip,block,owner.strip());
 }
 static Result compare(int originalStatus,String original,int status,String body,Rules rules,String expectedOwner){return compare(originalStatus,original,status,body,rules,expectedOwner,false);}
 static Result compare(int originalStatus,String original,int status,String body,Rules rules,String expectedOwner,boolean existenceVerified){
  if(originalStatus<200||originalStatus>=300)return result(AuthFinding.Kind.BASELINE,"기준 응답 확인 필요","");
  Map<String,Object> baseline=json(original,rules),candidate=json(body,rules);if(baseline!=null&&denied(baseline,rules))return result(AuthFinding.Kind.BASELINE,"기준 응답에 차단 규칙 일치","");
  if(status==401||status==403)return result(denialWithData(candidate,rules)?AuthFinding.Kind.DENIAL_DATA:AuthFinding.Kind.BLOCKED,denialWithData(candidate,rules)?"HTTP "+status+"와 데이터 필드 동시 관측 · 자료 노출 수동 확인":"HTTP "+status,"");
  if(status==404||status==410)return result(existenceVerified?AuthFinding.Kind.HIDDEN:AuthFinding.Kind.MISSING,existenceVerified?"소유자 재조회 2xx 동일 · 대상 HTTP "+status:"기준 2xx · 대상 HTTP "+status+" · 존재/삭제 여부 재검증 필요","");
  if(status>=300&&status<400)return result(AuthFinding.Kind.REVIEW,"리다이렉트 · 로그인 여부 확인","");
  if((status==400||status==422)&&candidate!=null&&denied(candidate,rules))return result(denialWithData(candidate,rules)?AuthFinding.Kind.DENIAL_DATA:AuthFinding.Kind.BLOCKED,denialWithData(candidate,rules)?"HTTP "+status+" · 설정한 JSON 차단 규칙과 자료 필드 동시 관측":"HTTP "+status+" · 설정한 JSON 차단 규칙 일치","");
  if(status<200||status>=300)return result(AuthFinding.Kind.REVIEW,"HTTP "+status,"");
  if(original.isBlank()||body.isBlank())return result(AuthFinding.Kind.REVIEW,"빈 성공 응답 · 데이터/실행 결과 확인","");
  // A CSV/file baseline must not prevent an explicit JSON denial from being inspected.
  if(candidate!=null&&denied(candidate,rules))return result(denialWithData(candidate,rules)?AuthFinding.Kind.DENIAL_DATA:AuthFinding.Kind.BLOCKED,denialWithData(candidate,rules)?"설정한 JSON 차단 규칙과 데이터 필드 동시 관측 · 자료 노출 수동 확인":"설정한 JSON 차단 규칙 일치","");
  if(AuthResponseData.emptyPayload(candidate))return result(AuthFinding.Kind.INCOMPLETE,"성공 응답의 사용자/자료가 비어 있음 · 접근 성공·차단 모두 미확정",baseline==null?"":changes(baseline,candidate,rules.ignored));
  if(rules.policy==AuthPolicy.Access.PUBLIC){Map<String,Object> publicBaseline=json(original,rules),publicJson=json(body,rules);if(publicBaseline!=null&&denied(publicBaseline,rules))return result(AuthFinding.Kind.BASELINE,"기준 응답에 차단 규칙 일치","");if(publicJson!=null&&denied(publicJson,rules))return result(AuthFinding.Kind.BLOCKED,"설정한 JSON 차단 규칙 일치","");return result(AuthFinding.Kind.ALLOWED,"설정한 경로·메서드 정책 · 성공 응답 관측","");}
  Map<String,Object> a=json(original,rules),b=json(body,rules);
  if((a==null||b==null)&&!rules.important.isEmpty())return result(AuthFinding.Kind.INCOMPLETE,"중요 JSON 필드 검증 불가 · JSON 응답 확인", "");
  if(a==null||b==null)return result(original.equals(body)?AuthFinding.Kind.SUSPECT:AuthFinding.Kind.REVIEW,original.equals(body)?"동일 성공 응답 · 수동 확인":"비 JSON/구조 제한 · 본문 수동 확인","");
  if(denied(a,rules))return result(AuthFinding.Kind.BASELINE,"기준 응답에 차단 규칙 일치","");
  String changes=changes(a,b,rules.ignored);
  if(denied(b,rules))return result(AuthFinding.Kind.BLOCKED,"설정한 JSON 차단 규칙 일치",changes);

  if(!rules.ownerPath.isBlank()&&!expectedOwner.isBlank()){
   Object before=value(a,rules.ownerPath),after=value(b,rules.ownerPath);
   if(before==null||after==null)return result(AuthFinding.Kind.INCOMPLETE,"소유 JSON 경로 누락 · "+rules.ownerPath+" · 원본/대상 필드 확인",changes);
   if(Objects.equals(before,after)&&!text(after).equals(expectedOwner))return rules.policy==AuthPolicy.Access.OWNER_ONLY?result(AuthFinding.Kind.OWNER,"소유자 전용 정책 · 소유 필드가 대상 계정 소유값과 다름",changes):result(AuthFinding.Kind.OWNER_UNKNOWN,"공개/공유/소유자 전용 정책 미설정 · 인가 위반 미확정",changes);
  }
  Map<String,Object> aa=without(a,rules.ignored),bb=without(b,rules.ignored);
  if(!rules.important.isEmpty()){
   var x=important(aa,rules.important);var y=important(bb,rules.important);
   boolean missing=rules.important.stream().anyMatch(field->x.keySet().stream().noneMatch(key->match(key,field))||y.keySet().stream().noneMatch(key->match(key,field)));
   if(missing)return result(AuthFinding.Kind.INCOMPLETE,"중요 필드 누락 · 응답 패킷 확인",changes);
   if(!x.isEmpty()&&x.equals(y))return result(AuthFinding.Kind.SUSPECT,"중요 데이터 필드 동일 · "+String.join(", ",rules.important)+" · 실제 접근 정책 확인",changes);
  }
  if(aa.isEmpty()||bb.isEmpty())return result(AuthFinding.Kind.REVIEW,"비교 가능한 JSON 필드 없음",changes);
  return aa.equals(bb)?result(AuthFinding.Kind.SUSPECT,"비교 대상 JSON 필드 동일 · 실제 접근 확인",changes):result(AuthFinding.Kind.REVIEW,"JSON 필드 차이 · 데이터/실행 결과 확인",changes);
 }
 private static Result result(AuthFinding.Kind s,String r,String c){return new Result(s,r,c);}
 static Map<String,Object> json(String body){return json(body,null);}
 static Map<String,Object> json(String body,Rules rules){try{Object doc=MinerJson.parse(body,128*1024,20000);if(rules!=null&&rules.unorderedArrays)doc=normalize(doc);if(!(doc instanceof Map<?,?>)&&!(doc instanceof List<?>))return null;Map<String,Object> out=new TreeMap<>();flatten(doc,"$",out);return out;}catch(Exception e){return null;}}
 private static void flatten(Object x,String path,Map<String,Object> out){if(out.size()>=8000)throw new IllegalArgumentException("필드 제한");if(x instanceof Map<?,?> m){if(m.isEmpty())out.put(path,Empty.OBJECT);else for(var e:m.entrySet())flatten(e.getValue(),path+(e.getKey().toString().matches("[A-Za-z_][A-Za-z0-9_]*")?"."+e.getKey():"["+MetadataExport.json(e.getKey())+"]"),out);}else if(x instanceof List<?> l){if(l.isEmpty())out.put(path,Empty.ARRAY);else for(int i=0;i<l.size();i++)flatten(l.get(i),path+"["+i+"]",out);}else out.put(path,x);}
 static Object normalize(Object value){if(value instanceof Map<?,?> map){Map<String,Object> next=new TreeMap<>();map.forEach((k,v)->next.put(k.toString(),normalize(v)));return next;}if(value instanceof List<?> array){List<Object> next=new ArrayList<>();for(Object v:array)next.add(normalize(v));next.sort(Comparator.comparing(MetadataExport::json));return next;}return value;}
 static boolean match(String path,String rule){String full=rule.startsWith("$")?rule:"$."+rule;if(path.equals(full))return true;if(full.contains("[*]")){String regex=java.util.Arrays.stream(full.split("\\[\\*\\]",-1)).map(java.util.regex.Pattern::quote).collect(java.util.stream.Collectors.joining("\\[[0-9]+\\]"));return path.matches(regex);}return !rule.startsWith("$")&&path.endsWith("."+rule);}
 static Map<String,Object> important(Map<String,Object> map,Set<String> fields){Map<String,Object> out=new TreeMap<>();map.forEach((k,v)->{if(fields.stream().anyMatch(f->match(k,f)))out.put(k,v);});return out;}
 static boolean denialWithData(Map<String,Object> map,Rules rules){
  if(map==null)return false;
  for(var entry:map.entrySet()){
   String key=entry.getKey();Object value=entry.getValue();
   if(value==null||value==Empty.OBJECT||value==Empty.ARRAY||value instanceof String s&&s.isBlank())continue;
   if(rules.denied.stream().anyMatch(rule->match(key,rule.path())))continue;
   if(!rules.ownerPath.isBlank()&&match(key,rules.ownerPath)||rules.important.stream().anyMatch(field->match(key,field)))return true;
   if(key.matches("\\$\\.(data|result|payload|items|records|content|file)(\\..*|\\[.*|$)"))return true;
  }
  return false;
 }
 static Set<String> paths(String text){if(text.length()>2048)throw new IllegalArgumentException("중요 필드 2KiB 제한");Set<String> fields=new LinkedHashSet<>();for(String s:text.split(","))if(!s.isBlank()){s=s.strip();if(s.length()>256||!s.startsWith("$"))throw new IllegalArgumentException("중요 필드는 $.ownerId, $.items[*].id 형식");fields.add(s);}if(fields.size()>32)throw new IllegalArgumentException("중요 필드 최대 32개");return fields;}
 static Object value(Map<String,Object> map,String path){String key=path.startsWith("$")?path:"$."+path;return map.get(key);}
 static boolean denied(Map<String,Object> map,Rules r){for(var rule:r.denied)for(var e:map.entrySet())if(match(e.getKey(),rule.path())&&text(e.getValue()).equals(rule.value()))return true;return false;}
 private static String text(Object v){if(v instanceof Number n){var d=new java.math.BigDecimal(n.toString()).stripTrailingZeros();return Math.abs((long)d.scale())<128&&d.precision()-(long)d.scale()<128?d.toPlainString():d.toString();}return String.valueOf(v);}
 static Map<String,Object> without(Map<String,Object> map,Set<String> ignored){Map<String,Object> out=new TreeMap<>();for(var e:map.entrySet())if(ignored.stream().noneMatch(r->match(e.getKey(),r)))out.put(e.getKey(),e.getValue());return out;}
 static String changes(Map<String,Object> a,Map<String,Object> b,Set<String> ignored){Set<String> keys=new TreeSet<>(a.keySet());keys.addAll(b.keySet());StringBuilder out=new StringBuilder();int count=0;for(String k:keys){if(ignored.stream().anyMatch(r->match(k,r)))continue;if(a.containsKey(k)!=b.containsKey(k)||!Objects.equals(a.get(k),b.get(k))){if(count++>=100){out.append("[차이 100개 제한]\n");break;}out.append(k).append(" : ").append(display(a,k)).append(" → ").append(display(b,k)).append('\n');}}return out.length()==0?"비교 대상 필드 차이 없음":out.toString();}
 private static String display(Map<String,Object> m,String k){if(!m.containsKey(k))return "[없음]";String s=MetadataExport.json(m.get(k));return s.length()>160?s.substring(0,160)+"…":s;}
}
