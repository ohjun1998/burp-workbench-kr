package kr.workbench;
import burp.api.montoya.MontoyaApi;import burp.api.montoya.http.*;import burp.api.montoya.http.message.requests.HttpRequest;import burp.api.montoya.http.message.responses.HttpResponse;
import java.util.*;
/** Explicit per-account GET refresh. No shared cookie jar, redirects, retries or persisted secrets. */
final class BountySession {
 record Config(boolean enabled,String url,String tokenPath,String tokenHeader,String prefix,String csrfPath,String csrfHeader,String identityPath){
  static Config off(){return new Config(false,"","","authorization","Bearer ","","x-csrf-token","");}
  Config{for(String s:List.of(url,tokenPath,tokenHeader,prefix,csrfPath,csrfHeader,identityPath))if(s.length()>2048||s.contains("\r")||s.contains("\n"))throw new IllegalArgumentException("갱신 설정 길이/줄바꿈 오류");
   for(String h:List.of(tokenHeader,csrfHeader))if(!h.matches("[A-Za-z][A-Za-z0-9-]{0,63}")||Set.of("host","content-length","connection","transfer-encoding","cookie").contains(h.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("갱신 대상은 인증/CSRF 헤더 이름이어야 합니다.");
   for(String p:List.of(tokenPath,csrfPath,identityPath))if(!p.isBlank()&&!p.startsWith("$."))throw new IllegalArgumentException("추출 경로는 $.token 형식입니다.");
  }
 }
 static Map<String,String> refresh(MontoyaApi api,Config cfg,Map<String,String> original,Set<String> names,String origin,String targetUrl,String expectedIdentity)throws Exception{
  if(!cfg.enabled)return original;if(original.isEmpty())throw new IllegalArgumentException("비로그인 슬롯은 갱신할 수 없습니다.");
  HttpRequest request=HttpRequest.httpRequestFromUrl(cfg.url);if(!WorkbenchScope.contains(api,request)||!AuthComparePanel.origin(request).equals(origin))throw new IllegalArgumentException("갱신 URL은 같은 Origin과 SCOPE 안이어야 합니다.");
  request=Core.sessionRequest(request,original,names);
  var rr=api.http().sendRequest(request,RequestOptions.requestOptions().withResponseTimeout(10000).withRedirectionMode(RedirectionMode.NEVER));
  if(rr==null||rr.response()==null||rr.response().statusCode()<200||rr.response().statusCode()>=300)throw new IllegalArgumentException("갱신 실패 · 비교 요청 전송 중단");
  if(rr.request()!=null&&(!AuthComparePanel.origin(rr.request()).equals(origin)||!AuthComparePanel.credentialValues(rr.request(),names).equals(AuthComparePanel.credentialValues(request,names))))throw new IllegalArgumentException("갱신 전송 세션 변경 · Burp 세션 규칙 확인");
  HttpResponse response=rr.response();if(response.toByteArray().length()>128*1024)throw new IllegalArgumentException("갱신 응답 128 KiB 제한");
  Map<String,Object> json=AuthJudgement.json(JsAnalysisPanel.readBody(response));
  if(!cfg.identityPath.isBlank()&&(expectedIdentity.isBlank()||!scalar(json,cfg.identityPath).equals(expectedIdentity)))throw new IllegalArgumentException("갱신 계정 신원 불일치 · 비교 중단");
  Map<String,String> next=new LinkedHashMap<>(original);
  if(!cfg.tokenPath.isBlank())next.put(cfg.tokenHeader.toLowerCase(Locale.ROOT),cfg.prefix+scalar(json,cfg.tokenPath));
  if(!cfg.csrfPath.isBlank())next.put(cfg.csrfHeader.toLowerCase(Locale.ROOT),scalar(json,cfg.csrfPath));
  // Update existing cookie names only; new cookies require explicit capture/assignment.
  Map<String,String> cookies=new LinkedHashMap<>();for(String pair:original.getOrDefault("cookie","").split(";")){int n=pair.indexOf('=');if(n>0)cookies.put(pair.substring(0,n).strip(),pair.substring(n+1).strip());}
  for(var h:response.headers())if(h.name().equalsIgnoreCase("set-cookie")){String first=h.value().split(";",2)[0];int n=first.indexOf('=');if(n>0&&cookies.containsKey(first.substring(0,n).strip())){java.net.URI refreshUri=java.net.URI.create(cfg.url),targetUri=java.net.URI.create(targetUrl);String defaultPath=refreshUri.getPath();defaultPath=defaultPath==null||!defaultPath.contains("/")?"/":defaultPath.substring(0,defaultPath.lastIndexOf('/'));if(defaultPath.isEmpty())defaultPath="/";String cookiePath=defaultPath;boolean applicable=true,deleted=false;for(String attr:h.value().split(";")){String a=attr.strip();if(a.toLowerCase(Locale.ROOT).startsWith("domain=")){String domain=a.substring(7).strip().toLowerCase(Locale.ROOT).replaceFirst("^\\.","");String host=refreshUri.getHost().toLowerCase(Locale.ROOT);if(!host.equals(domain)&&!host.endsWith("."+domain))applicable=false;}if(a.toLowerCase(Locale.ROOT).startsWith("path=")){cookiePath=a.substring(5);}if(a.toLowerCase(Locale.ROOT).matches("max-age=\\s*(-[0-9]+|0)"))deleted=true;if(a.equalsIgnoreCase("secure")&&!targetUri.getScheme().equals("https"))applicable=false;}String target=targetUri.getPath();if(!target.equals(cookiePath)&&!target.startsWith(cookiePath.endsWith("/")?cookiePath:cookiePath+"/"))applicable=false;if(!applicable)continue;if(deleted){cookies.remove(first.substring(0,n).strip());continue;}String value=first.substring(n+1).strip();validValue(value);cookies.put(first.substring(0,n).strip(),value);}}
  if(cookies.isEmpty())next.remove("cookie");else next.put("cookie",String.join("; ",cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList()));
  for(String value:next.values())validValue(value);return Collections.unmodifiableMap(next);
 }
 static String scalar(Map<String,Object> json,String path){Object v=json==null?null:AuthJudgement.value(json,path);if(!(v instanceof String||v instanceof Number||v instanceof Boolean))throw new IllegalArgumentException("갱신 JSON 추출 실패 · "+path);String s=String.valueOf(v);if(s.isBlank())throw new IllegalArgumentException("갱신 값 없음 · "+path);validValue(s);return s;}
 static void validValue(String s){if(s.length()>8192||s.contains("\r")||s.contains("\n")||s.contains("\0"))throw new IllegalArgumentException("갱신 헤더 값 오류");}
}
