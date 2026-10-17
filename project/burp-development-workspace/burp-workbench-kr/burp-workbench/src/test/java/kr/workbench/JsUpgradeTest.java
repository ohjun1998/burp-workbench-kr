package kr.workbench;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;
import java.util.zip.*;

/** Behavioural regression cases for the JS analysis upgrade; no target-server traffic. */
public final class JsUpgradeTest extends WorkbenchTest {
  static JsAnalysis.Endpoint endpoint(JsAnalysis.Result result,String suffix){return result.endpoints().stream().filter(e->e.url().endsWith(suffix)&&!e.method().equals("?")).findFirst().orElseThrow(()->new AssertionError(result));}
  static JsRequestDraft.Draft draft(JsAnalysis.Result result,JsAnalysis.Endpoint endpoint){return result.requestDrafts().get(JsAnalysis.draftKey(endpoint));}
  static byte[] gzip(byte[] bytes)throws Exception{var out=new ByteArrayOutputStream();try(var stream=new GZIPOutputStream(out)){stream.write(bytes);}return out.toByteArray();}
  public static void main(String[] args)throws Exception{
    String source="https://cdn.test/assets/app.js",page="https://app.test/shop/index.html";
    var context=JsAnalysis.analyzeContext("fetch('/api/orders');fetch('./items');",source,"application/javascript",page);
    check(endpoint(context,"/api/orders").url().equals("https://app.test/api/orders"),"CDN script root-relative URL resolves to the observed execution page");
    check(endpoint(context,"/shop/items").complete(),"document-relative URL uses document directory");
    var unknown=JsAnalysis.analyzeContext("fetch('/api/orders');",source,"application/javascript",null);
    check(!endpoint(unknown,"/api/orders").complete()&&endpoint(unknown,"/api/orders").url().equals("/api/orders"),"missing execution context never invents a CDN API host");
    check(unknown.requestDrafts().values().stream().allMatch(d->d.url().contains("REPLACE-HOST.invalid")),"unresolved relative host produces only a visibly blocked host placeholder");
    var absolute=JsAnalysis.analyzeContext("fetch('https://api.test/orders');",source,"application/javascript",null);
    check(endpoint(absolute,"/orders").complete(),"explicit absolute API host remains usable without page context");
    var html=JsAnalysis.analyzeContext("<script>fetch('./inline');</script>",page,"text/html",null);
    check(endpoint(html,"/shop/inline").complete(),"inline HTML uses its own document URL");
    check(JsOrigin.document(source,"application/javascript","javascript:alert(1)")==null,"invalid Referer cannot establish document origin");
    var axios=JsAnalysis.analyzeContext("const client=axios.create({baseURL:'/api/v2',headers:{'Content-Type':'application/json'},params:{lang:'ko'}});client.post('/orders',{productId:10,quantity:1});client.get('users');",source,"application/javascript",page);
    var order=endpoint(axios,"/api/v2/orders");
    check(order.method().equals("POST")&&order.bodyKeys().contains("quantity"),"Axios instance method and data fields are tracked");
    check(((Number)((Map<?,?>)MinerJson.parse(draft(axios,order).body())).get("productId")).intValue()==10&&draft(axios,order).url().endsWith("?lang=ko"),"Axios instance defaults and JSON constants reach the request draft");
    check(endpoint(axios,"/api/v2/users").method().equals("GET"),"Axios instance joins a path without a leading slash");
    var override=JsAnalysis.analyze("const c=axios.create({baseURL:'https://one.test',method:'POST'});c.request({url:'/x',baseURL:'https://two.test',method:'PUT',data:{n:3}});","");
    check(endpoint(override,"/x").url().equals("https://two.test/x")&&endpoint(override,"/x").method().equals("PUT"),"local Axios options override instance defaults");
    var wrapper=JsAnalysis.analyze("function send(url,data){return fetch(url,{method:'POST',body:JSON.stringify(data)});}send('/wrapped',{count:7});", "https://app.test/");
    var wrapped=endpoint(wrapper,"/wrapped");
    check(wrapped.method().equals("POST")&&!wrapper.endpoints().stream().anyMatch(e->e.url().endsWith("/wrapped")&&e.method().equals("GET")),"fetch wrapper never changes an internal POST into GET");
    check(draft(wrapper,wrapped).body().equals("{\"count\":7}"),"wrapper arguments retain body constants");
    var twice=JsAnalysis.analyze("function send(url,data){return fetch(url,{method:'POST',body:JSON.stringify(data)});}send('/wrapped',{count:1});send('/wrapped',{count:2});", "https://app.test/");
    check(twice.requestDrafts().values().stream().map(JsRequestDraft.Draft::body).distinct().count()==2,"distinct wrapper call sites preserve their own payloads");
    String graph="axios.post('/graphql',{query:'query User($id:ID!){user(id:$id){id}} mutation Save($name:String!){save(name:$name){id}}',variables:{id:1,name:'a'}});";
    var graphql=JsAnalysis.analyze(graph,"https://app.test/");
    var operations=graphql.endpoints().stream().filter(e->e.kind().contains("GraphQL")).toList();
    check(operations.size()==2&&operations.stream().anyMatch(e->e.kind().contains("query User"))&&operations.stream().anyMatch(e->e.kind().contains("mutation Save")),"GraphQL operations sharing one endpoint remain separately selectable");
    check(operations.stream().allMatch(e->draft(graphql,e)!=null)&&graphql.requestDrafts().values().stream().anyMatch(d->d.body().contains("\"operationName\":\"Save\"")),"each GraphQL operation carries its selected operationName in the draft");
    check(JsGraphql.operations("{\"query\":\"query User{user{name}}\",\"operationName\":\"User\"}").size()==1,"explicit GraphQL operation selection is respected");
    check(JsGraphql.operations("{\"query\":\"query Good{field(arg:\\\"mutation Fake\\\")} # mutation Comment\\n\"}").size()==1,"GraphQL strings and comments cannot fabricate operations");
    var grouped=JsFindingGroups.group(JsAnalysis.analyze("fetch('/same?a=1');fetch('/same?b=2');", "https://app.test/"));
    check(grouped.endpoints().size()==1&&grouped.endpoints().get(0).parameters().contains("a")&&grouped.endpoints().get(0).parameters().contains("b"),"API grouping retains parameter-name variants");
    check(grouped.endpoints().get(0).evidence().contains("발견 위치 2개")&&grouped.endpoints().get(0).evidence().contains("초안은 첫 발견 기준"),"grouping preserves provenance and explains draft selection");
    check(JsFindingGroups.priority(endpoint(JsAnalysis.analyze("fetch('/admin/upload');", "https://app.test/"),"/admin/upload")).startsWith("우선 검토"),"review priority cites relevant endpoint names without a vulnerability verdict");
    String large="/*"+"x".repeat(600*1024)+"*/;\nfetch('/tail',{method:'POST',body:JSON.stringify({x:2})});";
    var tail=JsBatchAnalysis.analyze(large,source,"application/javascript",page);var last=endpoint(tail,"/tail");
    check(last.start()>JsAnalysis.SOURCE_LIMIT&&last.method().equals("POST")&&draft(tail,last).body().contains("2"),"analysis reaches the tail beyond the old source prefix and preserves draft values");
    var many=new StringBuilder();for(int i=0;i<650;i++)many.append("fetch('/many").append(i).append("');");
    check(JsBatchAnalysis.analyze(many.toString(),source,"application/javascript",page).endpoints().size()==650,"production analysis does not silently stop at 500 endpoints");
    check(JsBodyDecoder.decode(Base64.getDecoder().decode("CwiAZmV0Y2goJy9icm90bGknKTsD"),"br","application/javascript").equals("fetch('/brotli');"),"Brotli response is decoded by the bundled relocated decoder");
    byte[] raw="fetch('/한글');".getBytes(StandardCharsets.UTF_8);
    check(JsBodyDecoder.decode(gzip(gzip(raw)),"gzip, gzip","application/javascript").equals(new String(raw,StandardCharsets.UTF_8)),"stacked content encodings are decoded in reverse order");
    check(JsBodyDecoder.decode(raw,"gzip","application/javascript").contains("한글"),"already decoded host body remains readable with retained gzip header");
    var out=new ByteArrayOutputStream();try(var deflater=new DeflaterOutputStream(out,new Deflater(6,true))){deflater.write(raw);}
    check(JsBodyDecoder.decode(out.toByteArray(),"deflate","application/javascript").contains("한글"),"raw deflate fallback is supported");
    boolean unsupported=false;try{JsBodyDecoder.decode(raw,"unknown","text/plain");}catch(IOException ex){unsupported=true;}check(unsupported,"unsupported compression is an explicit failure");
    String map=MetadataExport.json(Map.of("version",3,"sources",List.of("src/orders.js"),"sourcesContent",List.of("axios.post('/mapped',{count:4});")));
    var recovered=JsMiner.sourceMap(map,"https://app.test/app.js.map");var miner=new JsMiner.Result(List.of(),List.of(),List.of(),List.of(),recovered,"");
    var mapped=JsMapRecovery.merge(new JsAnalysis.Result(List.of(),List.of(),"test",false),miner,source,page);var mappedEndpoint=endpoint(mapped,"/mapped");
    check(mappedEndpoint.kind().equals("source map: src/orders.js")&&mappedEndpoint.start()<0&&mappedEndpoint.evidence().contains("원본 열"),"source map findings preserve recovered file and exact local position");
    check(draft(mapped,mappedEndpoint).body().equals("{\"count\":4}"),"source map request draft values survive merging");
    Path directory=Files.createTempDirectory("wb-js-upgrade-");
    try{
      var archive=new JsArchive(directory);archive.load();archive.save(source,large,page,"application/javascript",tail,new String[]{"request","response","decoded"},"abcdef0123456789");
      var restored=new JsArchive(directory);restored.load();var saved=restored.read(source);
      check(saved.source().equals(large)&&saved.result().endpoints().equals(tail.endpoints()),"disk history restores full source and findings independently of memory cache");
      check(saved.result().requestDrafts().isEmpty(),"rich request drafts are not serialized into analysis metadata");
      check(restored.snapshot().size()==1&&restored.versionKey(source,"newhash0123456789").contains("#wb-version="),"changed source receives a separate version rather than replacing prior findings");
      for(int i=0;i<105;i++){String key="https://app.test/"+i+".js";archive.save(key,"fetch('/x')",page,"application/javascript",context,null,"hash"+i);}
      check(archive.snapshot().size()==106&&archive.read(source)!=null,"disk analysis history survives more than 100 sources");
      var totals=new JsAnalysisTotals();totals.updateArchive(archive.snapshot().values(),List.of());check(totals.api.count==3,"archive aggregate counts deduplicate APIs across retained versions");
      archive.clear();check(archive.snapshot().isEmpty()&&Files.list(directory).findAny().isEmpty(),"explicit analysis reset removes project archive files");
    }finally{try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    System.out.println("JsUpgradeTest: "+checks+" checks passed");
  }
}
