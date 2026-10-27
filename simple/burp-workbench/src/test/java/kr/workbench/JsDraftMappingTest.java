package kr.workbench;
import java.util.*;
public final class JsDraftMappingTest extends WorkbenchTest {
 static JsAnalysis.Endpoint item(JsAnalysis.Result result,String kind){return result.endpoints().stream().filter(e->e.kind().equals(kind)).findFirst().orElseThrow();}
 static JsRequestDraft.Draft draft(JsAnalysis.Result result,String kind){return result.requestDrafts().get(JsAnalysis.draftKey(item(result,kind)));}
 public static void main(String[] args){
  String source="const page=3; const payload={name:'한글',count:2,enabled:true,absent:null,nested:{tags:['a',false,7]},dynamic:runtime,apiKey:'SOURCE_SECRET'}; const body=JSON.stringify(payload);fetch('/api/items?lang=ko&token=SOURCE_TOKEN',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'SOURCE_AUTH'},body:body});";
  var result=JsAnalysis.analyze(source,"https://app.test/assets/app.js");var request=draft(result,"fetch");
  check(request!=null&&request.method().equals("POST")&&request.url().equals("https://app.test/api/items?lang=ko&token=REPLACE_ME"),"selected source call retains confirmed URL query and masks credential values");
  var body=(Map<?,?>)MinerJson.parse(request.body());
  check(body.get("name").equals("한글")&&body.get("count").toString().equals("2")&&body.get("enabled").equals(true)&&body.containsKey("absent")&&body.get("absent")==null,"JSON.stringify constant body preserves strings numbers booleans and null");
  check(((Map<?,?>)body.get("nested")).get("tags").equals(List.of("a",false,new java.math.BigDecimal("7"))),"nested object and array types survive draft creation");
  check(body.get("dynamic").equals("REPLACE_ME")&&body.get("apiKey").equals("REPLACE_ME"),"runtime body values remain unresolved and credentials are not inserted");
  check(!request.wire().contains("SOURCE_")&&!request.wire().contains("Authorization:"),"draft does not silently copy source authorization");
  check(!result.toString().contains("lang=ko")&&!result.toString().contains("한글")&&!result.toString().contains("SOURCE_"),"metadata diagnostic representation excludes private draft values");
  check(item(result,"fetch").parameters().equals("lang, token")&&item(result,"fetch").bodyKeys().contains("name"),"metadata keeps query and JSON.stringify body names");
  var axios=JsAnalysis.analyze("const n=4;axios.post('/users?from=list',{count:n,active:false,nested:{v:-2}},{baseURL:'https://api.test/v1',params:{page:n,q:'a b',optional:null},headers:{'Content-Type':'application/json'}});","");var a=draft(axios,"axios.post");
  check(a.url().equals("https://api.test/v1/users?from=list&page=4&q=a+b&optional=REPLACE_ME"),"Axios baseURL query params and known scalar constants are combined without guessing null serialization");
  check(a.body().equals("{\"count\":4,\"active\":false,\"nested\":{\"v\":-2}}"),"Axios data retains exact nested JSON types");
  var form=JsAnalysis.analyze("axios.post('/form',{name:'a b',n:2},{headers:{'Content-Type':'application/x-www-form-urlencoded'}});","https://api.test/");
  check(draft(form,"axios.post").body().equals("name=a+b&n=2"),"explicit form draft encodes scalar fields");
  var text=JsAnalysis.analyze("axios.post('/text','hello',{headers:{'Content-Type':'text/plain'}});","https://api.test/");
  check(draft(text,"axios.post").body().equals("hello")&&draft(text,"axios.post").contentType().equals("text/plain"),"Axios scalar data is retained using its explicit content type");
  var string=JsAnalysis.analyze("fetch('/json',{method:'POST',headers:{'Content-Type':'application/json'},body:'{\"count\":3,\"secret\":\"hidden\"}'});","https://api.test/");
  check(draft(string,"fetch").body().equals("{\"count\":3,\"secret\":\"REPLACE_ME\"}"),"literal JSON body is parsed with sensitive fields masked");
  var unsupported=JsAnalysis.analyze("fetch('/object',{method:'POST',body:{x:1}});","https://api.test/");
  check(draft(unsupported,"fetch").body().equals("REPLACE_BODY"),"unsupported fetch object BodyInit is not silently converted into invented JSON");
  var dynamic=JsAnalysis.analyze("fetch('/dynamic/'+id,{method:verb,body:unknown});","https://api.test/");
  check(draft(dynamic,"fetch").method().equals("REPLACE_METHOD")&&draft(dynamic,"fetch").url().contains("REPLACE_PATH")&&draft(dynamic,"fetch").body().contains("REPLACE_ME"),"unknown method path and body stay visibly unresolved");
  var shadow=JsAnalysis.analyze("const count=4;function f(count){axios.post('/s',{count:count});}","https://api.test/");
  check(draft(shadow,"axios.post").body().contains("REPLACE_ME"),"function parameters cannot inherit unrelated outer constants");
  var sites=JsAnalysis.analyze("fetch('/one?q=a');fetch('/one?q=b');","https://api.test/");
  check(sites.requestDrafts().size()==2&&sites.requestDrafts().values().stream().map(JsRequestDraft.Draft::url).distinct().count()==2,"same URL at different source positions retains its own parameter values");
  var e=item(axios,"axios.post");var confirmed=JsRequestDraft.confirm(e,a.url(),"PUT",a);
  check(confirmed.method().equals("PUT")&&confirmed.url().equals(a.url())&&confirmed.body().equals(a.body()),"manual URL method confirmation preserves mapped query and body");
  var changed=JsRequestDraft.confirm(e,"https://api.test/v1/users?page=9","PATCH",a);
  check(changed.url().endsWith("?page=9")&&changed.body().equals(a.body()),"manually edited URL query overrides the prior source query");
  var tooMany=new StringBuilder("axios.post('/big',{");for(int i=0;i<70;i++)tooMany.append("x").append(i).append(":1,");tooMany.append("z:1});");
  check(draft(JsAnalysis.analyze(tooMany.toString(),"https://api.test/"),"axios.post").body().contains("REPLACE_ME"),"unsupported large object cannot send a silently truncated body");
  System.out.println("JsDraftMappingTest: "+checks+" checks passed");
 }
}
