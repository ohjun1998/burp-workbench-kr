package kr.workbench;
import java.util.*;import java.util.regex.*;
/** Response observations only; never active scans, exploit confirmation, or credential validation. */
final class PassiveFindings {
 static final int BODY_LIMIT=1024*1024;
 record Finding(String rule,String url,String location,String evidence){}
 record Analysis(List<Finding> findings,int inspectedCharacters,int totalCharacters,boolean complete,String note){}
 static List<Finding> analyze(String url,int status,String headers,String body){return analyzeDetailed(url,status,headers,body).findings();}
 static Analysis analyzeDetailed(String url,int status,String headers,String body){body=Objects.requireNonNullElse(body,"");headers=Objects.requireNonNullElse(headers,"");String safe=ActivityTracker.safeUrl(url);if(safe==null)return new Analysis(List.of(),0,body.length(),false,"URL 해석 불가");String limited=body.substring(0,Math.min(body.length(),BODY_LIMIT)),type=EnvironmentResponse.header(headers,"Content-Type").toLowerCase(Locale.ROOT);List<Finding> out=new ArrayList<>();
  boolean complete=body.length()<=BODY_LIMIT;String note=complete?"전체 본문 검사 · 취약점 부재를 보증하지 않음":"본문 앞 "+BODY_LIMIT+"자 / 전체 "+body.length()+"자 · 후반 미검사";
  String stripped=limited.stripLeading();
  if(type.contains("json")||stripped.startsWith("{")||stripped.startsWith("["))try{ResponseJsonFindings.inspect(MinerJson.parse(limited,BODY_LIMIT,20000),"",safe,out);}catch(IllegalArgumentException error){complete=false;note+=" · JSON 구조·크기 제한 또는 형식 오류로 구조 분석 미완료";}
  if(type.contains("text/html")&&status>=200&&status<300){for(String name:List.of("Content-Security-Policy","X-Content-Type-Options"))if(EnvironmentResponse.header(headers,name).isBlank())out.add(new Finding("HTML 응답 헤더 검토",safe,"Header: "+name,"헤더 미관측 · 정책 적합성 수동 검토"));if(safe.startsWith("https:")&&EnvironmentResponse.header(headers,"Strict-Transport-Security").isBlank())out.add(new Finding("HTTPS HSTS 검토",safe,"Header: Strict-Transport-Security","헤더 미관측 · HTTPS 범위/정책 확인"));}
  for(String line:headers.split("\\r?\\n"))if(line.toLowerCase(Locale.ROOT).startsWith("set-cookie:")){String value=line.substring(line.indexOf(':')+1).strip(),name=value.split("=",2)[0];if(!name.matches("(?i).*(?:session|sid|auth|token).*"))continue;String lower=value.toLowerCase(Locale.ROOT);for(String attribute:List.of("httponly","samesite"))if(!Pattern.compile("(?:^|;)\\s*"+attribute+"(?:=|;|$)").matcher(lower).find())out.add(new Finding("세션 쿠키 속성 검토",safe,"Set-Cookie: "+name,"미관측: "+attribute+" · 쿠키값 저장하지 않음"));if(safe.startsWith("https:")&&!Pattern.compile("(?:^|;)\\s*secure(?:;|$)").matcher(lower).find())out.add(new Finding("세션 쿠키 속성 검토",safe,"Set-Cookie: "+name,"미관측: secure · 쿠키값 저장하지 않음"));}
  Matcher errors=Pattern.compile("(?:BadSqlGrammarException|org\\.h2\\.jdbc\\.[A-Za-z]+|ORA-[0-9]{5}|SQLSTATE\\[[A-Z0-9]+]|java\\.[a-z.]+Exception)").matcher(limited);while(errors.find()&&out.size()<32)out.add(new Finding("오류 구현 정보 노출 후보",safe,"Body: "+errors.start(),errors.group()));
  Matcher sql=SqlErrors.PATTERN.matcher(limited);while(sql.find()&&out.size()<32)out.add(new Finding("오류 구현 정보 노출 후보",safe,"Body: "+sql.start(),sql.group().substring(0,Math.min(160,sql.group().length()))));
  if(safe.matches(".*(?:/\\.env|/config|/settings)(?:$|/.*)")){Matcher keys=Pattern.compile("(?im)(?:^|[\\s\"'])((?:DB_|DATABASE_|AWS_)?(?:PASSWORD|SECRET|ACCESS_KEY|API_KEY))\\s*[\"']?\\s*[:=]").matcher(limited);while(keys.find()&&out.size()<32)out.add(new Finding("설정 키 노출 후보",safe,"Body: "+keys.start(),keys.group(1)+" · 값/유효성 저장·검증 없음"));}
  if(out.size()>=32){complete=false;note+=" · 후보 32개 제한";}
  if(!complete)out.add(new Finding("응답 분석 범위 제한",safe,"Body",note));
  return new Analysis(List.copyOf(new LinkedHashSet<>(out)),limited.length(),body.length(),complete,note);
 }
}
