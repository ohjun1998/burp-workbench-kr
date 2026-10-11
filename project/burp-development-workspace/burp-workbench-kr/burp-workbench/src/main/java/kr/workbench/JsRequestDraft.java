package kr.workbench;
import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;
import burp.api.montoya.http.message.requests.HttpRequest;

/** A metadata-only request draft. Never sends requests or copies captured credentials. */
final class JsRequestDraft {
 record Draft(String url,String method,String body,String contentType) {
  Draft(String url,String method,String body){this(url,method,body,"application/json");}
  HttpRequest request(){HttpRequest q=HttpRequest.httpRequestFromUrl(url);if(!method.equals("GET"))q=q.withMethod(method);return body.isEmpty()?q:q.withHeader("Content-Type",contentType).withBody(body);}
 }
 static Draft create(JsAnalysis.Endpoint e,String resolved){return create(e,resolved,"application/json");}
 static Draft create(JsAnalysis.Endpoint e,String resolved,String contentType){
  String raw=resolved==null?e.url():resolved.strip();if(!e.complete()&&resolved==null)throw new IllegalArgumentException("동적 URL은 대상 설정에서 값을 확정하세요.");
  URI u;try{u=URI.create(raw);}catch(Exception ex){throw new IllegalArgumentException("URL을 확인하세요.");}
  if(u.getHost()==null||u.getUserInfo()!=null||!Set.of("http","https").contains(u.getScheme())||raw.contains("{dynamic}")||raw.length()>8192)throw new IllegalArgumentException("확정된 HTTP(S) URL이 필요합니다.");
  String method=e.method();if(!Set.of("GET","HEAD","OPTIONS","POST","PUT","PATCH","DELETE").contains(method))throw new IllegalArgumentException("메서드 미확정 · 원본에서 확인하세요.");
  String path=u.getRawPath();String base=u.getScheme()+"://"+u.getRawAuthority()+(path==null||path.isEmpty()?"/":path);StringJoiner query=new StringJoiner("&");
  for(String name:names(e.parameters()))query.add(URLEncoder.encode(name,StandardCharsets.UTF_8)+"=REPLACE_ME");
  Map<String,Object> body=new LinkedHashMap<>();for(String name:names(e.bodyKeys()))body.put(name,"REPLACE_ME");
  String type=contentType.split(";",2)[0].strip().toLowerCase(Locale.ROOT);if(!body.isEmpty()&&!Set.of("application/json","application/x-www-form-urlencoded").contains(type))throw new IllegalArgumentException("본문 형식 "+type+" · 실제 트래픽을 Repeater로 가져오세요.");String encoded=MetadataExport.json(body);if(type.equals("application/x-www-form-urlencoded")){StringJoiner form=new StringJoiner("&");for(String name:body.keySet())form.add(URLEncoder.encode(name,StandardCharsets.UTF_8)+"=REPLACE_ME");encoded=form.toString();}
  return new Draft(base+(query.length()==0?"":"?"+query),method,body.isEmpty()?"":encoded,type);
 }
 static Set<String> names(String text){Set<String> out=new LinkedHashSet<>();for(String n:text.split(",\\s*")){if(!n.isBlank()&&n.length()<=128&&out.size()<64)out.add(n);}return out;}
}
