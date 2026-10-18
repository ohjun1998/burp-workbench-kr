package kr.workbench;
import java.util.*;
import java.util.concurrent.atomic.*;
import kr.payloadrunner.Engine;
import static kr.workbench.WorkbenchTest.*;
public final class BankUpgradeTest {
 static int checks;
 static void ok(boolean b,String m){if(!b)throw new AssertionError(m);checks++;System.out.println("PASS "+m);}
 static String json(String query){return MetadataExport.json(Map.of("query",query));}
 static byte[] raw(int code,String body){return ("HTTP/1.1 "+code+" Test\r\nContent-Type: application/json\r\n\r\n"+body).getBytes(java.nio.charset.StandardCharsets.UTF_8);}
 static List<Engine.Row> sql(int budget,int falseStatus,boolean dynamic,boolean repeated)throws Exception{
   List<Engine.Row> rows=new ArrayList<>();AtomicInteger n=new AtomicInteger();
   var c=new Engine.Config(Engine.Profile.SQLI,Engine.Encoding.URL,"GET /"+Engine.MARK+" HTTP/1.1\r\nHost: test\r\n\r\n","a",List.of("a' AND '1'='1'","a' AND '1'='2'"),0,budget,"");
   var sum=Engine.run(c,q->{int i=n.incrementAndGet();return raw(i<4?200:falseStatus,i<4?(dynamic&&i==2?"dynamic":"base"):(i==5&&!repeated?"changed":"empty"));},new AtomicBoolean(),rows::add);
   ok(sum.requests()<=budget,"SQL request budget "+budget);return rows;
 }
 public static void main(String[] args)throws Exception{
   for(String q:List.of("{ me { id } }","query Own { me { id } }","# mutation ignored\nquery Q($id: ID!) { me(id:$id) { id } }","query Q { field(text:\"mutation { x }\") }"))ok(GraphqlSemantics.queryBody(json(q)),"single query accepted "+q);
   ok(GraphqlSemantics.queryBody(MetadataExport.json(Map.of("query","query Q { me }","operationName","Q"))),"matching operationName");
   for(String q:List.of("mutation { send }","subscription { events }","{ a } mutation M { send }","query Q { a } query R { b }","query Q { ...F } fragment F on User { id }","query Q { a","query Q { a ) }","{}","query Q { x(text:\"unfinished) }"))ok(!GraphqlSemantics.queryBody(json(q)),"ambiguous/write/malformed gate "+q);
   ok(!GraphqlSemantics.queryBody(MetadataExport.json(Map.of("query","query Q { me }","operationName","R"))),"operationName mismatch gated");
   ok(!GraphqlSemantics.queryBody("[{\"query\":\"{a}\"}]"),"batch keeps write gate");
   String denial="{\"data\":{\"transactionSummary\":null},\"errors\":[{\"message\":\"You can only query your own transaction summary.\\nGraphQL request: accountNumber ABC\"}]}";
   ok(GraphqlSemantics.response(denial)==GraphqlSemantics.Result.DENIED,"echoed query is not protected data");
   ok(GraphqlSemantics.response("{\"data\":{\"me\":null},\"errors\":[{\"extensions\":{\"code\":\"FORBIDDEN\"}}]}")==GraphqlSemantics.Result.DENIED,"coded denial");
   ok(GraphqlSemantics.response("{\"data\":{\"id\":1},\"errors\":[{\"message\":\"Forbidden\"}]}")==GraphqlSemantics.Result.ERROR,"partial data reviewed");
   ok(GraphqlSemantics.response("{\"errors\":[{\"message\":\"syntax error\"}]}")==GraphqlSemantics.Result.ERROR,"syntax error not denial");
   ok(GraphqlSemantics.response("{\"data\":{\"id\":1}}")==GraphqlSemantics.Result.DATA,"data success");
   ok(GraphqlSemantics.response("{\"data\":null}")==GraphqlSemantics.Result.UNKNOWN,"empty data not success");
   for(int status:List.of(404,410))ok(sql(5,status,false,true).stream().anyMatch(r->r.verdict().equals("SQL 조건 차이 후보")),"stable status split candidate "+status);
   for(int status:List.of(401,403,429,500))ok(sql(5,status,false,true).stream().noneMatch(r->r.verdict().equals("SQL 조건 차이 후보")),"blocked/error no boolean candidate "+status);
   ok(sql(4,404,false,true).stream().noneMatch(r->r.verdict().equals("SQL 조건 차이 후보")),"budget insufficient retains review");
   ok(sql(5,404,true,true).stream().noneMatch(r->r.verdict().equals("SQL 조건 차이 후보")),"dynamic baseline suppressed");
   ok(sql(5,404,false,false).stream().noneMatch(r->r.verdict().equals("SQL 조건 차이 후보")),"dynamic false suppressed");
   BountyFeaturesTest.factory();var request=BountyFeaturesTest.rq("http://test/state","GET","",List.of());
   var queryRequest=BountyFeaturesTest.rq("http://test/graphql","POST",json("query Q { me { id } }"),List.of(header("Content-Type","application/json")));
   ok(GraphqlSemantics.read(queryRequest),"JSON GraphQL endpoint read semantic");
   ok(!GraphqlSemantics.read(BountyFeaturesTest.rq("http://test/save","POST",json("{ me }"),List.of(header("Content-Type","application/json")))),"unrelated POST with query key keeps gate");
   ok(!GraphqlSemantics.read(BountyFeaturesTest.rq("http://test/graphql","POST",json("{ me }"),List.of())),"missing JSON content type keeps gate");
   var api=proxy(burp.api.montoya.MontoyaApi.class,(p,m,a)->def(m));AtomicReference<AuthComparePanel> panelRef=new AtomicReference<>();edt(()->panelRef.set(new AuthComparePanel(api,List::of)));var panel=panelRef.get();
   try{
     edt(()->{panel.observe(BountyFeaturesTest.rq("http://test/graphql","GET","",List.of(header("Cookie","SID=target"))),response(200,"{}"));panel.assign(0,panel.credentials.values().iterator().next());panel.matrixRules.setText("POST /graphql DENY ALLOW UNKNOWN UNKNOWN");});var run=new AuthComparePanel.Run(1,queryRequest,response(200,"{\"data\":{\"me\":{\"id\":1}}}"),panel.context());
     ok(AuthComparison.assess(run,0,queryRequest,response(400,denial),false).kind()==AuthFinding.Kind.BLOCKED,"GraphQL 400 denial integration");
     ok(AuthComparison.assess(run,1,queryRequest,response(200,"{\"data\":{\"me\":{\"id\":1}}}"),false).kind()==AuthFinding.Kind.ALLOWED,"GraphQL query not write-unverified");
     ok(AuthComparison.assess(run,0,queryRequest,response(200,"{\"data\":{\"id\":1},\"errors\":[{\"message\":\"Forbidden\"}]}"),false).verdict()==AuthVerdict.REVIEW,"partial GraphQL response not promoted");
     var bad=new AuthComparePanel.Run(2,queryRequest,response(200,"{\"data\":null}"),panel.context());ok(AuthComparison.assess(bad,0,queryRequest,response(400,denial),false).kind()==AuthFinding.Kind.BASELINE,"invalid GraphQL reference retained");
   }finally{edt(panel::close);}
   var config=new AuthEffectCheck.Config(true,"POST","/transfer","http://test/state","$.balance","0",AuthEffectCheck.Mode.DELTA_ABOVE,1);
   var before=AuthEffectCheck.before(config,request,q->response(200,"{\"balance\":1000.10}"));
   var evidence=AuthEffectCheck.after(config,before,q->response(200,"{\"balance\":1005.10}"),()->false);
   ok(evidence.outcome()==AuthEffectCheck.Outcome.OBSERVED&&evidence.note().contains("변화 5"),"decimal delta observed");
   ok(AuthEffectCheck.finding(evidence,BountyMatrix.Expected.ALLOW,new AuthFinding(AuthFinding.Kind.WRITE_UNVERIFIED,"","")).verdict()==AuthVerdict.SUSPECT,"business violation independent of allowed role");
   var unchanged=AuthEffectCheck.after(config,before,q->response(200,"{\"balance\":1000.10}"),()->false);
   ok(unchanged.outcome()==AuthEffectCheck.Outcome.UNCHANGED,"boundary equality not violation");
   var below=new AuthEffectCheck.Config(true,"POST","/transfer","http://test/state","$.balance","-2",AuthEffectCheck.Mode.DELTA_BELOW,1);
   ok(AuthEffectCheck.after(below,before,q->response(200,"{\"balance\":997.10}"),()->false).outcome()==AuthEffectCheck.Outcome.OBSERVED,"decrease lower bound");
   for(String body:List.of("{}","{\"balance\":\"NaN\"}","{\"balance\":null}")){try{AuthEffectCheck.after(config,before,q->response(200,body),()->false);throw new AssertionError("invalid numeric response");}catch(IllegalArgumentException e){ok(true,"invalid numeric field reviewed");}}
   System.out.println("BankUpgradeTest: "+checks+" checks passed");
 }
}
