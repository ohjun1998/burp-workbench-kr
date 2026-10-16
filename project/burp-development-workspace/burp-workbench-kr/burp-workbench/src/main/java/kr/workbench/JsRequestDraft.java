package kr.workbench;
import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;
import burp.api.montoya.http.message.requests.HttpRequest;

/** An explicit request draft. Building a draft never sends it or copies captured credentials. */
final class JsRequestDraft {
 record Draft(String url,String method,String body,String contentType) {
  Draft(String url,String method,String body){this(url,method,body,"application/json");}
  HttpRequest request(){HttpRequest q=HttpRequest.httpRequestFromUrl(url);if(!method.equals("GET"))q=q.withMethod(method);return body.isEmpty()?q:q.withHeader("Content-Type",contentType).withBody(body);}
  String wire(){URI u=URI.create(url);String path=u.getRawPath();String target=(path==null||path.isEmpty()?"/":path)+(u.getRawQuery()==null?"":"?"+u.getRawQuery());String head=method+" "+target+" HTTP/1.1\r\nHost: "+u.getRawAuthority()+"\r\nAccept: application/json\r\n";if(!body.isEmpty())head+="Content-Type: "+contentType+"\r\nContent-Length: "+body.getBytes(StandardCharsets.UTF_8).length+"\r\n";return head+"\r\n"+body;}
 }
 static Draft create(JsAnalysis.Endpoint e,String resolved){return create(e,resolved,"application/json");}
 /** Unresolved candidates open the editor immediately, without inventing a method or path value. */
 static Draft preview(JsAnalysis.Endpoint e){
  String url=e.url().replaceAll("\\{[^}]*}","REPLACE_PATH").replaceAll("(?<=/):[A-Za-z_$][A-Za-z0-9_$]*","REPLACE_PATH");
  boolean known=Set.of("GET","HEAD","OPTIONS","POST","PUT","PATCH","DELETE").contains(e.method());
  var safe=new JsAnalysis.Endpoint(e.url(),known?e.method():"GET",e.kind(),e.parameters(),e.headers(),e.bodyKeys(),e.line(),e.start(),e.length(),e.complete(),e.evidence());
  Draft draft=create(safe,url);
  return new Draft(draft.url(),known?e.method():"REPLACE_METHOD",draft.body(),draft.contentType());
 }
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
 static Draft confirm(JsAnalysis.Endpoint endpoint,String resolved,String method,Draft mapped){
  var confirmed=new JsAnalysis.Endpoint(endpoint.url(),method,endpoint.kind(),endpoint.parameters(),endpoint.headers(),mapped==null?endpoint.bodyKeys():"",endpoint.line(),endpoint.start(),endpoint.length(),true,endpoint.evidence());
  Draft safe=create(confirmed,resolved,mapped==null?"application/json":mapped.contentType());
  URI url=URI.create(resolved.strip());
  String target=url.getScheme()+"://"+url.getRawAuthority()+(url.getRawPath()==null||url.getRawPath().isEmpty()?"/":url.getRawPath());
  String query=url.getRawQuery();
  return new Draft(query==null?safe.url():target+"?"+query,method,mapped==null?safe.body():mapped.body(),mapped==null?safe.contentType():mapped.contentType());
 }
 static Set<String> names(String text){Set<String> out=new LinkedHashSet<>();for(String n:text.split(",\\s*")){if(!n.isBlank()&&n.length()<=128&&out.size()<64)out.add(n);}return out;}
}
