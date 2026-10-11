package kr.workbench;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Classification never changes the original URL used for a request. */
final class ReconMetadata {
 static Set<String> parameters(String url) {
  Set<String> names=new TreeSet<>();
  try {String q=URI.create(url).getRawQuery();if(q!=null)for(String pair:q.split("&")) {String key=URLDecoder.decode(pair.split("=",2)[0],StandardCharsets.UTF_8);if(!key.isBlank()&&key.length()<=256&&names.size()<200)names.add(key);}}catch(Exception ignored){}
  return names;
 }
 static String pattern(String url) {
  try {URI u=URI.create(url);String path=u.getRawPath();if(path==null||path.isEmpty())path="/";
   path=path.replaceAll("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}","{UUID}")
    .replaceAll("(?<=/)[0-9]+(?=/|$)","{ID}").replaceAll("(?i)(?<=/)[0-9a-f]{16,}(?=/|$)","{HASH}");
   return u.getScheme().toLowerCase(Locale.ROOT)+"://"+u.getRawAuthority().toLowerCase(Locale.ROOT)+path+" | "+String.join(",",parameters(url));
  }catch(Exception ex){return "";}
 }
 static boolean staticAsset(String url){try{return URI.create(url).getPath().toLowerCase(Locale.ROOT).matches(".*\\.(?:png|jpe?g|gif|svg|css|woff2?|ico|eot|ttf|mp4|webp|mp3)$");}catch(Exception ex){return false;}}
 static boolean dangerous(String url){try {String p=URLDecoder.decode(URI.create(url).getRawPath(),StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);return p.matches(".*(?:^|[/_.-])(?:logout|signout|delete|remove|revoke|destroy)(?:$|[/_.-]).*")||parameters(url).stream().anyMatch(k->k.matches("(?i)(?:logout|signout|delete|remove|revoke|destroy)"));}catch(Exception ex){return true;}}
 static String category(String url){
  String p;try{p=URI.create(url).getPath().toLowerCase(Locale.ROOT);}catch(Exception ex){return "기타";}
  String keys=String.join(" ",parameters(url)).toLowerCase(Locale.ROOT),s=p+" "+keys;
  if(p.matches(".*(?:/admin|/internal|/manage|/backoffice|/cms|/console|/debug|/actuator|/swagger|/api-docs|/dashboard).*"))return "관리자/내부";
  if(s.matches(".*(?:transfer|checkout|refund|coupon|invoice|withdraw|payment|billing|wallet|balance|transaction|mileage|cart).*"))return "자금/결제";
  if(s.matches(".*(?:login|register|reset-password|oauth|sso|session|signin|signup|auth|token|mfa|otp|password|saml).*"))return "인증";
  if(s.matches(".*(?:upload|download|export|import|avatar|attachment|document|file|report).*"))return "파일 처리";
  if(parameters(url).stream().anyMatch(k->k.matches("(?i)(?:.*[_-])?(?:id|no|num|seq|idx|key|uuid)")))return "객체 접근";
  if(p.matches(".*?/(?:[0-9]+|[0-9a-f]{8}-[0-9a-f-]{27,}|[0-9a-f]{16,})(?:/.*)?"))return "객체 접근";
  return "기타";
 }
 static String risk(String url){
  try {String p=URI.create(url).getPath().toLowerCase(Locale.ROOT);
   if(dangerous(url))return "상태 변경 주의";
   if(p.matches(".*\\.(?:env|bak|swp|old|sql|sqlite|db|dump|log|config|properties|ya?ml|ini)$")||p.matches(".*?/\\.(?:git|svn|hg|aws|ssh|docker)(?:/.*|$)"))return "민감 파일 후보";
   if(parameters(url).stream().anyMatch(k->k.matches("(?i).*(?:token|password|secret|api.?key|jwt|pwd).*")))return "인증정보 파라미터";
   if(category(url).equals("관리자/내부"))return "관리 경로 후보";
  }catch(Exception ignored){}return "";
 }
 record Discovery(String url,String method,Set<String> query,Set<String> body,String source){}
 static List<Discovery> discover(String base,String text,boolean javascript) {
  List<Discovery> out=new ArrayList<>();Set<String> seen=new HashSet<>();
  if(text==null)return out;text=text.substring(0,Math.min(text.length(),JsAnalysis.SOURCE_LIMIT));
  if(!javascript)for(var e:ApiInventory.parse(text,base))if(!e.template())add(out,seen,base,e.url(),e.method(),e.query(),e.body(),"OpenAPI "+e.method());
  if(javascript||text.toLowerCase(Locale.ROOT).contains("<script"))for(var e:JsAnalysis.analyze(text,base).endpoints()) {
   if(!e.complete()||e.url().contains("{dynamic}"))continue;
   add(out,seen,base,e.url(),e.method(),names(e.parameters()),names(e.bodyKeys()),"JS");
  }
  if(!javascript){Matcher m=Pattern.compile("(?i)(?:href|src|action)\\s*=\\s*[\"']([^\"'<>]{1,8192})[\"']").matcher(text);
   while(m.find()&&out.size()<500)add(out,seen,base,m.group(1).replace("&amp;","&"),"?",Set.of(),Set.of(),"HTML");
   Matcher forms=Pattern.compile("(?is)<form\\b([^>]{0,8192})>(.*?)</form>").matcher(text);
   while(forms.find()&&out.size()<500){String attrs=forms.group(1),action=attr(attrs,"action"),method=attr(attrs,"method").equalsIgnoreCase("post")?"POST":"GET";Set<String> keys=new TreeSet<>();Matcher input=Pattern.compile("(?is)<(?:input|select|textarea)\\b([^>]{0,8192})>").matcher(forms.group(2));while(input.find()&&keys.size()<200){String n=attr(input.group(1),"name");if(!n.isBlank()&&n.length()<257)keys.add(n);}add(out,seen,base,action.isBlank()?base:action,method,method.equals("GET")?keys:Set.of(),method.equals("POST")?keys:Set.of(),"HTML form");}
  }
  return out;
 }
 private static String attr(String s,String key){Matcher m=Pattern.compile("(?i)\\b"+key+"\\s*=\\s*(?:[\"']([^\"']*)[\"']|([^\\s>]+))").matcher(s);return m.find()?(m.group(1)==null?m.group(2):m.group(1)):"";}
 private static Set<String> names(String s){Set<String> result=new TreeSet<>();if(s!=null)for(String n:s.split(",\\s*"))if(!n.isBlank()&&n.length()<=256&&result.size()<200)result.add(n);return result;}
 private static void add(List<Discovery> out,Set<String> seen,String base,String ref,String method,Set<String> query,Set<String> body,String source){try {String url=URI.create(base).resolve(ref).toString();URI u=URI.create(url);if(u.getUserInfo()!=null||u.getHost()==null||!Set.of("http","https").contains(u.getScheme())||url.length()>8192)return;int fragment=url.indexOf('#');if(fragment>=0)url=url.substring(0,fragment);Set<String> keys=new TreeSet<>(parameters(url));keys.addAll(query);if(seen.add(method+" "+url+keys+body))out.add(new Discovery(url,method,keys,body,source));}catch(Exception ignored){}}
}
