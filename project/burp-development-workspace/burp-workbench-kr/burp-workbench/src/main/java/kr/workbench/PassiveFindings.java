package kr.workbench;
import java.util.*;import java.util.regex.*;
/** Response observations only; never active scans, exploit confirmation, or credential validation. */
final class PassiveFindings {
 record Finding(String rule,String url,String location,String evidence){}
 static List<Finding> analyze(String url,int status,String headers,String body){String safe=ActivityTracker.safeUrl(url);if(safe==null)return List.of();String limited=body.substring(0,Math.min(body.length(),65536)),type=EnvironmentResponse.header(headers,"Content-Type").toLowerCase(Locale.ROOT);List<Finding> out=new ArrayList<>();
  if(type.contains("text/html")&&status>=200&&status<300){for(String name:List.of("Content-Security-Policy","X-Content-Type-Options"))if(EnvironmentResponse.header(headers,name).isBlank())out.add(new Finding("HTML 응답 헤더 검토",safe,"Header: "+name,"헤더 미관측 · 정책 적합성 수동 검토"));if(safe.startsWith("https:")&&EnvironmentResponse.header(headers,"Strict-Transport-Security").isBlank())out.add(new Finding("HTTPS HSTS 검토",safe,"Header: Strict-Transport-Security","헤더 미관측 · HTTPS 범위/정책 확인"));}
  for(String line:headers.split("\\r?\\n"))if(line.toLowerCase(Locale.ROOT).startsWith("set-cookie:")){String value=line.substring(line.indexOf(':')+1).strip(),name=value.split("=",2)[0];if(!name.matches("(?i).*(?:session|sid|auth|token).*"))continue;String lower=value.toLowerCase(Locale.ROOT);for(String attribute:List.of("httponly","samesite"))if(!Pattern.compile("(?:^|;)\\s*"+attribute+"(?:=|;|$)").matcher(lower).find())out.add(new Finding("세션 쿠키 속성 검토",safe,"Set-Cookie: "+name,"미관측: "+attribute+" · 쿠키값 저장하지 않음"));if(safe.startsWith("https:")&&!Pattern.compile("(?:^|;)\\s*secure(?:;|$)").matcher(lower).find())out.add(new Finding("세션 쿠키 속성 검토",safe,"Set-Cookie: "+name,"미관측: secure · 쿠키값 저장하지 않음"));}
  Matcher errors=Pattern.compile("(?:BadSqlGrammarException|org\\.h2\\.jdbc\\.[A-Za-z]+|ORA-[0-9]{5}|SQLSTATE\\[[A-Z0-9]+]|java\\.[a-z.]+Exception)").matcher(limited);while(errors.find()&&out.size()<32)out.add(new Finding("오류 구현 정보 노출 후보",safe,"Body: "+errors.start(),errors.group()));
  Matcher sql=SqlErrors.PATTERN.matcher(limited);while(sql.find()&&out.size()<32)out.add(new Finding("오류 구현 정보 노출 후보",safe,"Body: "+sql.start(),sql.group().substring(0,Math.min(160,sql.group().length()))));
  if(safe.matches(".*(?:/\\.env|/config|/settings)(?:$|/.*)")){Matcher keys=Pattern.compile("(?im)(?:^|[\\s\"'])((?:DB_|DATABASE_|AWS_)?(?:PASSWORD|SECRET|ACCESS_KEY|API_KEY))\\s*[\"']?\\s*[:=]").matcher(limited);while(keys.find()&&out.size()<32)out.add(new Finding("설정 키 노출 후보",safe,"Body: "+keys.start(),keys.group(1)+" · 값/유효성 저장·검증 없음"));}
  return List.copyOf(new LinkedHashSet<>(out));
 }
}
