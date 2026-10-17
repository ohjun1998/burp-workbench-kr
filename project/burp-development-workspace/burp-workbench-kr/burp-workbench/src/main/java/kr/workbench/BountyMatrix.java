package kr.workbench;
import java.net.URI;import java.util.*;import java.util.regex.Pattern;
/** Reviewer-declared policy per operation and account slot. Role/tenant names are metadata. */
final class BountyMatrix {
 enum Expected{UNKNOWN,ALLOW,DENY}
 record Rule(String method,String path,List<Expected> slots,Pattern match){}
 final List<Rule> rules;
 BountyMatrix(List<Rule> rules){this.rules=List.copyOf(rules);}
 static BountyMatrix parse(String text){if(text.length()>8192)throw new IllegalArgumentException("권한 매트릭스 8KB 제한");List<Rule> rules=new ArrayList<>();Set<String> unique=new HashSet<>();
  for(String line:text.split("\\R")){if(line.isBlank())continue;String[] p=line.strip().split("\\s+");if(p.length!=5)throw new IllegalArgumentException("매트릭스: GET /경로 ALLOW DENY DENY (계정 1/2/3)");
   var base=AuthPolicy.parse(p[0]+" "+p[1]+" PUBLIC",AuthPolicy.Access.UNSPECIFIED).rules.get(0);List<Expected> slots=new ArrayList<>();for(int i=2;i<5;i++)try{slots.add(Expected.valueOf(p[i]));}catch(Exception e){throw new IllegalArgumentException("기대 권한은 ALLOW / DENY / UNKNOWN");}
   if(!unique.add(p[0]+" "+p[1]))throw new IllegalArgumentException("중복 매트릭스 경로");rules.add(new Rule(p[0],p[1],slots,base.match()));if(rules.size()>32)throw new IllegalArgumentException("매트릭스 최대 32개");}
  rules.sort(Comparator.comparingInt((Rule r)->r.path.contains("{")?0:1).reversed().thenComparing(Comparator.comparingInt((Rule r)->r.path.length()).reversed()));return new BountyMatrix(rules);
 }
 Expected expected(String method,String url,int slot){String path=URI.create(url).getPath();for(Rule r:rules)if((r.method.equals("*")||r.method.equals(method))&&r.match.matcher(path).matches())return r.slots.get(slot);return Expected.UNKNOWN;}
 String assess(Expected e,int status,String body,AuthJudgement.Rules rules){return assess(e,status,body,rules,false);}
 String assess(Expected e,int status,String body,AuthJudgement.Rules rules,boolean existenceVerified){if(e==Expected.UNKNOWN)return "";
  boolean blocked=status==401||status==403;var json=AuthJudgement.json(body);if(json!=null&&AuthJudgement.denied(json,rules))blocked=true;
  if(status==404||status==410){if(existenceVerified&&e==Expected.DENY)return "기대 DENY · 차단 근거 일치 · 소유자 재조회 2xx 동일 · 대상 HTTP "+status;return "기대 "+e+" · 404/410 존재·세션 재검토"+(existenceVerified?" · 소유자 재조회 2xx 동일":"");}
  if(blocked)return e==Expected.DENY?"기대 DENY · 차단 근거 일치":"기대 ALLOW · 차단 관측 · 세션/정책 재검토";
  if(status>=200&&status<300&&!body.isBlank())return e==Expected.DENY?"기대 DENY · 성공 응답 관측 · 권한 위반 후보, 수동 확인":"기대 ALLOW · 성공 응답 관측";
  return "기대 "+e+" · 판정 보류 · 실행 결과 확인";
 }
}
