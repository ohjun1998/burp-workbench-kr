package kr.workbench;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
public final class JsLabEngineTest {
 static int checks=0;
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
 static JsAnalysis.Endpoint endpoint(JsAnalysis.Result result,String suffix){return result.endpoints().stream().filter(e->e.url().endsWith(suffix)).findFirst().orElseThrow(()->new AssertionError(suffix));}
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]);Map<String,JsAnalysis.Result> results=new LinkedHashMap<>();
  for(int i=1;i<=14;i++){final String prefix=String.format("%02d-",i);Path p;try(var paths=Files.list(root)){p=paths.filter(x->x.getFileName().toString().startsWith(prefix)&&!x.toString().endsWith(".map")).findFirst().orElseThrow();}
   var result=JsAnalysis.analyze(Files.readString(p),"http://127.0.0.1:8088/assets/js-lab/"+p.getFileName());results.put(p.getFileName().toString(),result);
   check(!result.truncated(),p+" must not be truncated");
  }
  var fetch=results.get("01-fetch.js");check(fetch.status().contains("AST 블록 1"),"fetch AST");
  var comments=endpoint(fetch,"/comments");check(comments.method().equals("POST"),"fetch method");check(comments.headers().contains("X-Lab-Trace"),"fetch header");check(comments.bodyKeys().contains("postId"),"fetch body");check(endpoint(fetch,"/posts").parameters().equals("page, category"),"query names");check(endpoint(fetch,"/profile").method().equals("PUT"),"Request constructor");check(endpoint(fetch,"/edit").method().equals("PATCH"),"const options");
  var axios=results.get("02-axios.js");check(endpoint(axios,"/search").parameters().equals("q, page"),"Axios params");check(endpoint(axios,"/upload").bodyKeys().equals("fileId, filename"),"Axios body");check(endpoint(axios,"/audit").method().equals("POST"),"Axios config");check(endpoint(axios,"/export").method().equals("HEAD"),"Axios request");
  var xhr=results.get("03-xhr-navigation.js");check(endpoint(xhr,"/xhr").kind().contains("XHR"),"XHR");check(xhr.endpoints().stream().anyMatch(e->e.method().equals("UPGRADE")),"WebSocket");check(endpoint(xhr,"/help").kind().equals("window.open"),"window.open");
  var constant=results.get("04-constants.js");check(endpoint(constant,"/constant").complete(),"const concat");check(endpoint(constant,"/template").complete(),"static template");check(endpoint(constant,"/escaped/path").complete(),"escape");check(endpoint(constant,"/assets/relative").complete(),"relative URL");
  var dynamic=results.get("05-dynamic-scope.js");check(!endpoint(dynamic,"/users/{dynamic}").complete(),"dynamic template");check(!endpoint(dynamic,"/post/{dynamic}").complete(),"dynamic concat");check(dynamic.endpoints().stream().noneMatch(e->e.url().endsWith("/api/js-lab/shadow")),"shadowing");check(dynamic.endpoints().stream().noneMatch(e->e.url().endsWith("/numeric/12")),"numeric concatenation");check(dynamic.endpoints().stream().filter(e->e.url().endsWith("/repeated")).count()==2,"repeated code locations");
  var secrets=results.get("06-secrets.js");for(String rule:List.of("AWS Access Key ID","Google API Key","GitHub token","Slack token","Stripe secret key","JWT / JWS","Private key"))check(secrets.secrets().stream().anyMatch(s->s.rule().equals(rule)),"secret "+rule);
  check(secrets.secrets().stream().anyMatch(s->s.rule().equals("시크릿 이름: secret")),"entropy context");check(secrets.secrets().stream().allMatch(s->s.masked().startsWith("[가림")),"masking");
  var negative=results.get("07-negative.js");check(negative.secrets().isEmpty(),"placeholder suppression");check(negative.endpoints().stream().noneMatch(e->e.url().contains("LAB_")||e.url().contains("user:")),"URL credential removal");
  var module=results.get("08-modern-module.js");check(!module.status().contains("AST 실패"),"module compatibility parsing");check(endpoint(module,"/modern").method().equals("GET")&&endpoint(module,"/modern").complete(),"module static URL resolved");check(module.miner().dependencies().size()==3,"module packages");
  var invalid=results.get("09-invalid.js");check(invalid.status().contains("AST 실패"),"invalid AST");check(invalid.secrets().size()==1,"invalid secret fallback");
  check(results.get("10-recon.js").miner().clouds().size()==6,"cloud providers");
  var map=results.get("11-sourcemap.js");check(map.miner().maps().size()==1&&map.miner().recovered().isEmpty(),"external map not auto requested");
  var inline=results.get("12-inline-map.js");check(inline.miner().recovered().size()==1,"inline source recovery");check(endpoint(inline,"/recovered").bodyKeys().equals("reportId"),"recovered AST");
  var html=results.get("13-inline.html");check(endpoint(html,"/html-inline").line()==4,"HTML source line");check(html.endpoints().stream().noneMatch(e->e.url().endsWith("/json-block")||e.url().endsWith("01-fetch.js")),"HTML excluded blocks");
  check(results.get("14-package.json").miner().dependencies().size()==3,"package manifest");
  var external=JsAnalysis.analyze(Files.readString(root.resolve("11-sourcemap.js.map")),"http://127.0.0.1:8088/assets/js-lab/11-sourcemap.js.map");check(external.miner().recovered().size()==1,"captured external map");check(external.endpoints().stream().anyMatch(e->e.url().endsWith("/recovered")&&e.method().equals("POST")&&e.kind().startsWith("source map:")),"external recovered method");
  results.put("11-sourcemap.js.map",external);
  var report=new LinkedHashMap<String,Object>();report.put("engine","Workbench KR 0.18.0 JsAnalysis + JsMiner");report.put("checks",checks);report.put("results",results);
  new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),report);
  System.out.println(checks+" checks passed; "+results.size()+" fixture responses analyzed");
 }
}
