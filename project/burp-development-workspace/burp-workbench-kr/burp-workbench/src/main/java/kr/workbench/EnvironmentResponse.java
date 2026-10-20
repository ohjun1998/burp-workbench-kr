package kr.workbench;
import java.net.URI;
import java.util.*;
/** Bounded comparison only: 200, redirects and access denials never prove a vulnerability. */
final class EnvironmentResponse {
 record Baseline(int status,String signature,String type){}
 static String header(String headers,String key){for(String line:headers.split("\\r?\\n")){int colon=line.indexOf(':');if(colon>0&&line.substring(0,colon).trim().equalsIgnoreCase(key))return line.substring(colon+1).trim();}return "";}
 static String signature(String text,String url){if(text!=null&&text.length()>EnvironmentContent.MAX)return "";String s=text==null?"":text;try{URI u=URI.create(url);String path=u.getRawPath();for(String value:List.of(url,path,path.replace("/","&#47;"),path.replace("/","&#x2F;"),path.replace("/","&#x2f;"),path.replace("/","&sol;"),path.replace("/","%2F"),path.replace("/","%2f")))if(!value.isEmpty()&&!value.equals("/"))s=s.replaceAll("(?<![\\p{L}\\p{N}_])"+java.util.regex.Pattern.quote(value)+"(?![\\p{L}\\p{N}_./%\\-])",java.util.regex.Matcher.quoteReplacement("{path}"));}catch(Exception ignored){}return s.replaceAll("\\s+"," ").trim();}
 static Baseline baseline(int status,String headers,String text,String url){return new Baseline(status,signature(text,url),header(headers,"Content-Type").split(";",2)[0].toLowerCase(Locale.ROOT));}
 static String state(int code,String headers,String body,String url,Baseline baseline){
  if(code==401||code==403)return "HTTP "+code+" · 접근 제한";
  if(code==404||code==410)return "HTTP "+code+" · 미발견";
  if(code==429)return "HTTP 429 · 요청 제한";
  if(code>=300&&code<400){String location=header(headers,"Location");return "HTTP "+code+(location.matches("(?i).*(login|signin|auth).*" )?" · 로그인 이동":" · 이동 응답");}
  if(code>=200&&code<300){Baseline sample=baseline(code,headers,body,url);if(baseline!=null&&baseline.status==code&&!sample.signature.isEmpty()&&sample.type.equals(baseline.type)&&sample.signature.equals(baseline.signature))return "HTTP "+code+" · 없는 경로와 동일";
   return "HTTP "+code+" · 응답 검토";}
  return "HTTP "+code+" · 오류/미판정";
 }
}
