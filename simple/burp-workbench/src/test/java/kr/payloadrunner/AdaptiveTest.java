package kr.payloadrunner;
import java.net.URLDecoder;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.atomic.*;
import static kr.payloadrunner.Engine.*;
public final class AdaptiveTest {
 static void ok(String n,boolean v){EngineTest.assertTest("adaptive: "+n,v);}
 static String value(byte[] r){String line=new String(r,StandardCharsets.ISO_8859_1).split("\\r\\n",2)[0];String u=line.split(" ")[1];return URLDecoder.decode(u.substring(u.indexOf("q=")+2),StandardCharsets.UTF_8);}
 static byte[] raw(String body,String mime,int status){return EngineTest.raw(status,mime,body);}
 static Config config(Profile p,List<String> payloads,int budget){return new Config(p,Encoding.URL,"GET /?q=§PAYLOAD§ HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n","alpha",payloads,0,budget,"");}
 static List<Row> rows;static Adaptive.Result run(Profile p,List<String> payloads,int budget,Transport tx)throws Exception{rows=new ArrayList<>();return Adaptive.run(config(p,payloads,budget),tx,new AtomicBoolean(),rows::add);}
 static boolean has(String v){return rows.stream().anyMatch(r->r.verdict().contains(v));}
 public static void run()throws Exception{
  var a=run(Profile.XSS,defaults(Profile.XSS),100,r->raw("<html>"+value(r)+"</html>","text/html",200));
  ok("HTML reflection revalidated and stops after one variant",a.requests()==6&&has("XSS 반사 후보 · 재현")&&a.skipped()>0);
  a=run(Profile.XSS,defaults(Profile.XSS),100,r->raw("<html>no reflection</html>","text/html",200));
  ok("non-reflection costs three requests and labels stored/DOM untested",a.requests()==3&&!has("후보")&&a.reason().contains("stored/DOM"));
  a=run(Profile.XSS,defaults(Profile.XSS),100,r->raw(value(r),"application/json",200));ok("JSON reflection pruned, DOM consumption explicitly untested",a.requests()==3&&a.reason().contains("DOM"));
  a=run(Profile.XSS,defaults(Profile.XSS),100,r->raw("<html>"+value(r).replace("<","&lt;").replace(">","&gt;")+"</html>","text/html",200));ok("escaped reflection never called XSS",!has("XSS 반사 후보"));
  a=run(Profile.XSS,defaults(Profile.XSS),100,r->raw("<div>"+value(r).replace("<","&lt;").replace(">","&gt;").replace("'","&#39;")+"</div>","text/html",200));ok("encoded tag in HTML text prunes variants with limitation",a.requests()==4&&a.reason().contains("우회/stored/DOM"));
  a=run(Profile.XSS,defaults(Profile.XSS),100,r->raw("<script>const x='"+value(r).replace("<","&lt;").replace(">","&gt;").replace("'","&#39;")+"'</script>","text/html",200));ok("encoded reflection in script does not activate text-context pruning",a.requests()>4&&!a.reason().contains("남은 정형 태그"));
  List<String> sent=new ArrayList<>();run(Profile.XSS,defaults(Profile.XSS),100,r->{String q=value(r);sent.add(q);return raw("<textarea>"+q+"</textarea>","text/html",200);});ok("textarea context prioritizes closing textarea",sent.get(3).startsWith("</textarea>"));
  a=run(Profile.SQLI,defaults(Profile.SQLI),100,r->raw(value(r).contains("1=2")||value(r).contains("'1'='2'")?"no rows":"normal rows","text/plain",200));ok("SQL true/false, reverse-order pair and late baseline corroborated",a.requests()==8&&has("SQL 조건 차이 재현 후보")&&a.skipped()>0);
  for(int status:new int[]{404,410}){a=run(Profile.SQLI,List.of("alpha AND 1=1","alpha AND 1=2"),12,r->raw(value(r).contains("1=2")?"no rows":"normal rows","text/plain",value(r).contains("1=2")?status:200));ok("SQL stable HTTP split with reverse controls "+status,has("SQL 조건 차이 재현 후보"));}
  a=run(Profile.SQLI,List.of("alpha AND 1=1","alpha AND 1=2"),4,r->raw(value(r).contains("1=2")?"no rows":"normal rows","text/plain",value(r).contains("1=2")?404:200));ok("SQL split without repeat budget stays inconclusive",!has("SQL 조건 차이 재현 후보"));
  AtomicInteger falseResponses=new AtomicInteger();a=run(Profile.SQLI,List.of("alpha AND 1=1","alpha AND 1=2"),12,r->raw(value(r).contains("1=2")?"no rows":"normal rows","text/plain",value(r).contains("1=2")?(falseResponses.incrementAndGet()==1?404:410):200));ok("SQL repeated false status must agree",!has("SQL 조건 차이 재현 후보"));
  a=run(Profile.SQLI,defaults(Profile.SQLI),100,r->raw(value(r),"text/plain",200));ok("SQL payload reflection alone no condition evidence",!has("SQL 조건 차이"));
  AtomicInteger n=new AtomicInteger();a=run(Profile.SQLI,defaults(Profile.SQLI),100,r->raw("normal rows "+n.incrementAndGet(),"text/plain",200));ok("dynamic baseline disables boolean evidence without masking fields",!has("SQL 조건 차이")&&rows.stream().anyMatch(r->r.evidence().contains("동적")));
  a=run(Profile.SQLI,defaults(Profile.SQLI),100,r->raw("SQLSTATE[42000]: reflected probe "+value(r),"text/plain",200));ok("pre-existing SQL errors not fresh evidence",!has("SQL 오류 후보"));
  a=run(Profile.SQLI,List.of("SQLSTATE[42000]: reflected probe"),100,r->raw(value(r),"text/plain",200));ok("reflected DB error excluded",!has("SQL 오류 후보"));
  sent.clear();a=run(Profile.SSTI,defaults(Profile.SSTI),100,r->{String q=value(r);sent.add(q);return raw(q.replace("{{731*919}}","671789").replace("{{731*920}}","672520"),"text/plain",200);});ok("SSTI needs different arithmetic with unique marker",a.requests()==5&&has("표현식 평가 후보 · 재현")&&sent.get(3).contains("731*920")&&!sent.get(2).equals(sent.get(3)));
  a=run(Profile.SSTI,defaults(Profile.SSTI),100,r->raw(value(r),"text/plain",200));ok("SSTI raw reflection not evaluated",!has("표현식 평가"));
  AtomicInteger transientHit=new AtomicInteger();a=run(Profile.SSTI,defaults(Profile.SSTI),100,r->raw(transientHit.incrementAndGet()==3?value(r).replace("{{731*919}}","671789"):value(r),"text/plain",200));ok("single transient evaluation never corroborated",!has(" · 재현"));
  n.set(0);a=run(Profile.XSS,defaults(Profile.XSS),100,r->{n.incrementAndGet();return raw("rate limited","text/html",429);});ok("429 halts after first request",a.requests()==1&&n.get()==1&&a.reason().contains("429"));
  a=run(Profile.SQLI,defaults(Profile.SQLI),100,r->raw((value(r).contains("1=2")||value(r).contains("'1'='2'"))?"blocked":"normal rows","text/plain",(value(r).contains("1=2")||value(r).contains("'1'='2'"))?403:200));ok("403 pair blocked and never SQL condition evidence",a.requests()==5&&!has("SQL 조건 차이")&&a.reason().contains("403"));
  a=run(Profile.CUSTOM,List.of("x","x","alpha","y"),100,r->raw("ok","text/plain",200));ok("duplicate literal and baseline payload omitted",a.requests()==4);
  for(int budget=1;budget<=7;budget++){n.set(0);final int b=budget;a=run(Profile.XSS,defaults(Profile.XSS),budget,r->{n.incrementAndGet();return raw(value(r),"text/html",200);});ok("budget "+b+" includes discovery/controls/repeats",a.requests()<=b&&n.get()==a.requests());}
  var stop=new AtomicBoolean();n.set(0);Adaptive.run(config(Profile.XSS,defaults(Profile.XSS),100),r->{n.incrementAndGet();return raw("ok","text/html",200);},stop,r->stop.set(true));ok("Stop prevents stability and discovery requests",n.get()==1);
  a=run(Profile.UPLOAD_NAME,List.of("probe.html","probe.html","probe.svg"),100,r->raw("accepted","text/plain",200));ok("upload deduped, no repeat or stability mutation",a.requests()==3&&!has(" · 재현"));
  a=run(Profile.SSRF,List.of("http://localhost/probe"),100,r->raw("generic","text/plain",200));ok("SSRF not called safe without OAST evidence",a.reason().contains("blind SSRF")&&!has(" · 재현"));
  n.set(0);a=run(Profile.CUSTOM,List.of("a","b","c","d","e"),100,r->{if(n.incrementAndGet()>2)throw new java.io.IOException("fixture");return raw("ok","text/plain",200);});ok("three transport failures halt future requests",a.requests()==5&&a.reason().contains("3회"));
  a=run(Profile.XSS,defaults(Profile.XSS),100,r->raw("a".repeat(LIMIT+1),"text/html",200));ok("truncated responses never prune XSS as non-reflecting",a.requests()>3&&!a.reason().contains("마커 없음"));
 }
}
